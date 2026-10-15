import Foundation
import SQLite3

/// SQLite op de systeemlibrary van Apple (iPhone, iPad en de iOS-app op de Mac).
/// Eén serial queue per geopend bestand; openen, sluiten en verwijderen lopen over één beheerqueue.
/// De verbindingen horen bij het proces, niet bij een pagina: na een herlaad van de WebView komt bij openen de bestaande verbinding terug.
final class SQLiteService {
    static let gedeeld: SQLiteService = {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let bundel = (Bundle.main.resourceURL ?? Bundle.main.bundleURL).appendingPathComponent("public")
        //dezelfde map als de vorige Cordova-plugin (location 'default'), uitgesloten van de backup
        return SQLiteService(bundelMap: bundel, dataMap: library.appendingPathComponent("LocalDatabase"))
    }()

    private final class Geopend {
        let id: String
        let sleutel: String
        let verbinding: SQLiteVerbinding
        let queue: DispatchQueue
        let info: [String: Any]

        init(id: String, sleutel: String, verbinding: SQLiteVerbinding, info: [String: Any]) {
            self.id = id
            self.sleutel = sleutel
            self.verbinding = verbinding
            self.queue = DispatchQueue(label: "nl.ncv.sqlite." + verbinding.naam)
            self.info = info
        }
    }

    private let bundelMap: URL
    private let dataMap: URL
    private let fm = FileManager.default
    private let beheer = DispatchQueue(label: "nl.ncv.sqlite.beheer")
    private let slot = NSLock()
    private var geopend: [String: Geopend] = [:]//databaseId → verbinding
    private var perBestand: [String: String] = [:]//modus/naam → databaseId

    init(bundelMap: URL, dataMap: URL) {
        self.bundelMap = bundelMap
        self.dataMap = dataMap
    }

    private func metSlot<T>(_ blok: () throws -> T) rethrows -> T {
        slot.lock()
        defer { slot.unlock() }
        return try blok()
    }

    // MARK: openen, sluiten, verwijderen

    func open(naam: String, modus: String, seed: String?, klaar: @escaping (Result<[String: Any], Error>) -> Void) {
        beheer.async {
            klaar(Result { try self.openNu(naam: naam, modus: modus, seed: seed) })
        }
    }

    private func openNu(naam: String, modus: String, seed: String?) throws -> [String: Any] {
        try controleerNaam(naam)
        if let seed = seed {
            try controleerNaam(seed)
        }
        let sleutel = modus + "/" + naam
        if let bestaand = metSlot({ perBestand[sleutel].flatMap { geopend[$0] } }) {
            var info = bestaand.info
            info["reused"] = true
            return info
        }

        let verbinding: SQLiteVerbinding
        var info: [String: Any]
        switch modus {
        case "bundled":
            let url = bundelMap.appendingPathComponent(naam)
            guard fm.fileExists(atPath: url.path) else {
                throw SQLiteFout("ASSET_NOT_FOUND", "\(naam) staat niet in de app", database: naam)
            }
            //onveranderlijke appinhoud: direct uit de bundel, zonder locking en wijzigingsdetectie
            verbinding = try SQLiteVerbinding(pad: url.absoluteString + "?mode=ro&immutable=1", flags: SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, naam: naam)
            info = ["readOnly": true, "source": "bundle", "copied": false]
        case "persistent":
            try fm.createDirectory(at: dataMap, withIntermediateDirectories: true)
            var map = dataMap
            var waarden = URLResourceValues()
            waarden.isExcludedFromBackup = true
            try? map.setResourceValues(waarden)

            let url = dataMap.appendingPathComponent(naam)
            var gekopieerd = false
            if let seed = seed, !fm.fileExists(atPath: url.path) {
                try kopieerSeed(seed, naar: url)
                gekopieerd = true
            }
            //met een seed nooit stil een lege database aanmaken
            let flags = SQLITE_OPEN_READWRITE | (seed == nil ? SQLITE_OPEN_CREATE : 0)
            verbinding = try SQLiteVerbinding(pad: url.path, flags: flags, naam: naam)
            info = ["readOnly": false, "source": "file", "copied": gekopieerd]
        default:
            throw SQLiteFout("INVALID_ARGUMENT", "Onbekende modus \(modus)", database: naam)
        }

        do {
            try verbinding.instellen(schrijfbaar: modus == "persistent")
            info["userVersion"] = try verbinding.userVersion()
        } catch {
            verbinding.sluiten()
            throw error
        }
        let id = UUID().uuidString
        info["databaseId"] = id
        info["reused"] = false
        info["sqliteVersion"] = String(cString: sqlite3_libversion())
        let nieuw = Geopend(id: id, sleutel: sleutel, verbinding: verbinding, info: info)
        metSlot {
            geopend[id] = nieuw
            perBestand[sleutel] = id
        }
        return info
    }

    /// Kopie via een tijdelijk bestand op hetzelfde volume; pas na de rename bestaat de database.
    private func kopieerSeed(_ seed: String, naar doel: URL) throws {
        let bron = bundelMap.appendingPathComponent(seed)
        guard fm.fileExists(atPath: bron.path) else {
            throw SQLiteFout("ASSET_NOT_FOUND", "\(seed) staat niet in de app", database: doel.lastPathComponent)
        }
        //losse journal/WAL-bestanden van een eerder verwijderde database mogen niet op de nieuwe seed worden toegepast
        try verwijderBijbestanden(van: doel)
        let tijdelijk = doel.deletingLastPathComponent().appendingPathComponent("." + doel.lastPathComponent + ".seed")
        do {
            if fm.fileExists(atPath: tijdelijk.path) {
                try fm.removeItem(at: tijdelijk)
            }
            try fm.copyItem(at: bron, to: tijdelijk)
            guard rename(tijdelijk.path, doel.path) == 0 else {
                throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
            }
        } catch {
            try? fm.removeItem(at: tijdelijk)
            throw SQLiteFout("COPY_FAILED", "\(seed) kopiëren mislukt: \(error.localizedDescription)", database: doel.lastPathComponent)
        }
    }

    private func verwijderBijbestanden(van url: URL) throws {
        for achtervoegsel in ["-journal", "-wal", "-shm"] {
            let pad = url.path + achtervoegsel
            if fm.fileExists(atPath: pad) {
                try fm.removeItem(atPath: pad)
            }
        }
    }

    /// Sluit na alle eerder aangeboden aanvragen van deze database; nieuwe aanvragen krijgen direct `DB_CLOSED`.
    /// Afmelden en het sluiten in de queue zetten gebeurt onder hetzelfde slot als het toelaten van aanvragen.
    /// Het resultaat komt via de beheerqueue, zodat een daarna aangeboden openen of verwijderen pas na het sluiten loopt.
    func sluiten(id: String, klaar: @escaping (Result<[String: Any], Error>) -> Void) {
        let weg: Geopend? = metSlot {
            guard let item = geopend.removeValue(forKey: id) else {
                return nil
            }
            if perBestand[item.sleutel] == id {
                perBestand.removeValue(forKey: item.sleutel)
            }
            item.queue.async { item.verbinding.sluiten() }
            return item
        }
        guard let item = weg else {
            klaar(.failure(SQLiteFout("DB_CLOSED", "Database is niet (meer) open")))
            return
        }
        beheer.async {
            item.queue.sync {}
            klaar(.success([:]))
        }
    }

    /// Verwijdert een gesloten `persistent`-database met journal/WAL-bestanden. Een ontbrekend bestand is geen fout.
    func verwijderen(naam: String, klaar: @escaping (Result<[String: Any], Error>) -> Void) {
        beheer.async {
            klaar(Result {
                try self.controleerNaam(naam)
                if self.metSlot({ self.perBestand["persistent/" + naam] != nil }) {
                    throw SQLiteFout("DB_IN_USE", "\(naam) is nog open", database: naam)
                }
                let url = self.dataMap.appendingPathComponent(naam)
                do {
                    if self.fm.fileExists(atPath: url.path) {
                        try self.fm.removeItem(at: url)
                    }
                    try self.verwijderBijbestanden(van: url)
                } catch {
                    throw SQLiteFout("DELETE_FAILED", "\(naam) verwijderen mislukt: \(error.localizedDescription)", database: naam)
                }
                return [:]
            })
        }
    }

    // MARK: uitvoeren

    /// Zet het werk onder het slot in de queue: een gelijktijdig sluiten komt er altijd vóór of na, nooit tussendoor.
    private func aanbieden<T>(_ id: String, _ klaar: @escaping (Result<T, Error>) -> Void, _ werk: @escaping (SQLiteVerbinding) throws -> T) {
        let aangeboden: Bool = metSlot {
            guard let item = geopend[id] else {
                return false
            }
            item.queue.async { klaar(Result { try werk(item.verbinding) }) }
            return true
        }
        if !aangeboden {
            klaar(.failure(SQLiteFout("DB_CLOSED", "Database is niet (meer) open")))
        }
    }

    func uitvoeren(id: String, sql: String, waarden: [Any], resultaat: SQLiteResultaat, klaar: @escaping (Result<[[String: Any]], Error>) -> Void) {
        aanbieden(id, klaar) { try $0.uitvoeren(sql, waarden, resultaat) }
    }

    func batch(id: String, statements: [(sql: String, waarden: [Any])], resultaat: SQLiteResultaat, klaar: @escaping (Result<[[[String: Any]]], Error>) -> Void) {
        aanbieden(id, klaar) { try $0.batch(statements, resultaat) }
    }

    private func controleerNaam(_ naam: String) throws {
        let toegestaan = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        guard !naam.isEmpty, !naam.hasPrefix("."), naam.unicodeScalars.allSatisfy({ toegestaan.contains($0) }) else {
            throw SQLiteFout("INVALID_ARGUMENT", "Ongeldige bestandsnaam: \(naam)")
        }
    }
}
