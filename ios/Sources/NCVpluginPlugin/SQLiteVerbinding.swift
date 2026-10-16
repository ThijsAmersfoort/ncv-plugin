import Foundation
import SQLite3

/// Fout met een vaste code voor JavaScript (`error.code`) en waar mogelijk de (uitgebreide) SQLite-code.
struct SQLiteFout: Error {
    let code: String
    let message: String
    var sqliteCode: Int32?
    var statementIndex: Int?
    var database: String?

    init(_ code: String, _ message: String, sqliteCode: Int32? = nil, statementIndex: Int? = nil, database: String? = nil) {
        self.code = code
        self.message = message
        self.sqliteCode = sqliteCode
        self.statementIndex = statementIndex
        self.database = database
    }

    var data: [String: Any] {
        var data: [String: Any] = [:]
        if let sqliteCode = sqliteCode { data["sqliteCode"] = Int(sqliteCode) }
        if let statementIndex = statementIndex { data["statementIndex"] = statementIndex }
        if let database = database { data["database"] = database }
        return data
    }
}

enum SQLiteResultaat: String {
    case none, first, all
}

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
//grootste geheel getal dat JavaScript exact kent; daarbuiten {"$int": "..."}
private let maxVeiligGetal: Int64 = 9_007_199_254_740_991

/// Eén SQLite-verbinding. Alleen gebruiken vanaf de queue van de bijbehorende database.
final class SQLiteVerbinding {
    let naam: String
    private var db: OpaquePointer?

    init(pad: String, flags: Int32, naam: String) throws {
        self.naam = naam
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(pad, &handle, flags, nil)
        guard rc == SQLITE_OK, let geopend = handle else {
            let melding = handle.map { String(cString: sqlite3_errmsg($0)) } ?? String(cString: sqlite3_errstr(rc))
            sqlite3_close_v2(handle)
            throw SQLiteFout("OPEN_FAILED", "\(naam) openen mislukt: \(melding)", sqliteCode: rc, database: naam)
        }
        db = geopend
        sqlite3_extended_result_codes(geopend, 1)
    }

    deinit {
        sluiten()
    }

    func sluiten() {
        if let db = db {
            sqlite3_close_v2(db)
        }
        db = nil
    }

    /// Vaste instellingen, gelijk aan de compileeropties van de vorige Cordova-engine (SYNCHRONOUS=3, TEMP_STORE=2).
    func instellen(schrijfbaar: Bool) throws {
        try exec("PRAGMA temp_store = MEMORY")
        if schrijfbaar {
            try exec("PRAGMA synchronous = EXTRA")
            try exec("PRAGMA foreign_keys = OFF")
        }
        //leest het schema: een bestand dat geen database is valt hier al af
        _ = try uitvoeren("SELECT count(*) FROM sqlite_master", [], .none)
    }

    func userVersion() throws -> Int {
        let rijen = try uitvoeren("PRAGMA user_version", [], .first)
        return (rijen.first?["user_version"] as? NSNumber)?.intValue ?? 0
    }

    private func handle() throws -> OpaquePointer {
        guard let db = db else {
            throw SQLiteFout("DB_CLOSED", "Database \(naam) is gesloten", database: naam)
        }
        return db
    }

    private func sqlFout(_ rc: Int32, _ db: OpaquePointer) -> SQLiteFout {
        SQLiteFout("SQL_ERROR", String(cString: sqlite3_errmsg(db)), sqliteCode: rc, database: naam)
    }

    func exec(_ sql: String) throws {
        let db = try handle()
        let rc = sqlite3_exec(db, sql, nil, nil, nil)
        if rc != SQLITE_OK {
            throw sqlFout(rc, db)
        }
    }

    /// Voert precies één statement uit. Rijen alleen bij `first`/`all`; het statement loopt altijd tot het einde.
    func uitvoeren(_ sql: String, _ waarden: [Any], _ resultaat: SQLiteResultaat) throws -> [[String: Any]] {
        let db = try handle()
        return try sql.withCString { tekst -> [[String: Any]] in
            var statement: OpaquePointer?
            var rest: UnsafePointer<CChar>?
            let rc = sqlite3_prepare_v2(db, tekst, -1, &statement, &rest)
            guard rc == SQLITE_OK else {
                throw sqlFout(rc, db)
            }
            guard let stmt = statement else {
                try controleerRest(db, rest)
                return []//alleen witruimte, commentaar of een losse puntkomma
            }
            defer { sqlite3_finalize(stmt) }
            try controleerRest(db, rest)
            try binden(db, stmt, waarden)
            return try stappen(db, stmt, resultaat)
        }
    }

    /// Alle statements in één transactie: `BEGIN IMMEDIATE` om te schrijven, `BEGIN DEFERRED` voor rijen per statement.
    func batch(_ statements: [(sql: String, waarden: [Any])], _ resultaat: SQLiteResultaat) throws -> [[[String: Any]]] {
        let db = try handle()
        try exec(resultaat == .none ? "BEGIN IMMEDIATE" : "BEGIN DEFERRED")
        do {
            var uitkomst: [[[String: Any]]] = []
            for (index, statement) in statements.enumerated() {
                if SQLiteVerbinding.isTransactiecontrole(statement.sql) {
                    throw SQLiteFout("TRANSACTION_CONTROL", "Transactiecontrole hoort niet in een batch", statementIndex: index, database: naam)
                }
                do {
                    let rijen = try uitvoeren(statement.sql, statement.waarden, resultaat)
                    if resultaat != .none {
                        uitkomst.append(rijen)
                    }
                } catch let fout as SQLiteFout {
                    var metIndex = fout
                    metIndex.statementIndex = index
                    throw metIndex
                }
                //vangnet: de transactie mag tussendoor niet beëindigd zijn
                if sqlite3_get_autocommit(db) != 0 {
                    throw SQLiteFout("TRANSACTION_CONTROL", "Transactiecontrole hoort niet in een batch", statementIndex: index, database: naam)
                }
            }
            try exec("COMMIT")
            return uitkomst
        } catch {
            //ook als COMMIT mislukt; de oorspronkelijke fout blijft de uitkomst
            if sqlite3_get_autocommit(db) == 0 {
                sqlite3_exec(db, "ROLLBACK", nil, nil, nil)
            }
            throw error
        }
    }

    /// BEGIN, COMMIT, END, ROLLBACK, SAVEPOINT en RELEASE horen niet in een batch: ze zouden de transactie stil beëindigen.
    /// Vóór uitvoering herkend aan het eerste woord (na witruimte en commentaar); een trigger begint met CREATE.
    static func isTransactiecontrole(_ sql: String) -> Bool {
        var tekens = Substring(sql)
        while true {
            tekens = tekens.drop(while: { $0.isWhitespace || $0 == ";" || $0 == "\u{FEFF}" })//SQLite slaat ook een BOM over
            if tekens.hasPrefix("--") {
                tekens = tekens.drop(while: { $0 != "\n" })
            } else if tekens.hasPrefix("/*") {
                guard let einde = tekens.range(of: "*/") else {
                    return false
                }
                tekens = tekens[einde.upperBound...]
            } else {
                break
            }
        }
        let woord = tekens.prefix(while: { $0.isLetter }).uppercased()
        return ["BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE"].contains(woord)
    }

    //sqlite3_prepare leest alleen het eerste statement: meer statements in één string zijn een fout, geen stille halve uitvoering
    private func controleerRest(_ db: OpaquePointer, _ begin: UnsafePointer<CChar>?) throws {
        var rest = begin
        while let huidig = rest, huidig.pointee != 0 {
            var volgende: OpaquePointer?
            var na: UnsafePointer<CChar>?
            let rc = sqlite3_prepare_v2(db, huidig, -1, &volgende, &na)
            if volgende != nil || rc != SQLITE_OK {
                sqlite3_finalize(volgende)
                throw SQLiteFout("MULTIPLE_STATEMENTS", "Meer dan één SQL-statement in één aanroep", database: naam)
            }
            if na == huidig {
                break
            }
            rest = na
        }
    }

    private func binden(_ db: OpaquePointer, _ stmt: OpaquePointer, _ waarden: [Any]) throws {
        for (positie, waarde) in waarden.enumerated() {
            let index = Int32(positie + 1)
            let rc: Int32
            switch waarde {
            case is NSNull:
                rc = sqlite3_bind_null(stmt, index)
            case let getal as NSNumber:
                rc = try bindGetal(stmt, index, getal)
            case let tekst as String:
                //expliciete lengte: ook tekst met een NUL-teken blijft heel
                rc = tekst.withCString { sqlite3_bind_text(stmt, index, $0, Int32(tekst.utf8.count), SQLITE_TRANSIENT) }
            case let object as [String: Any]:
                rc = try bindGetagd(stmt, index, object)
            default:
                throw SQLiteFout("INVALID_VALUE", "Ongeldige waarde voor parameter \(positie + 1)", database: naam)
            }
            if rc != SQLITE_OK {
                throw sqlFout(rc, db)
            }
        }
    }

    private func bindGetal(_ stmt: OpaquePointer, _ index: Int32, _ getal: NSNumber) throws -> Int32 {
        if CFGetTypeID(getal) == CFBooleanGetTypeID() {
            return sqlite3_bind_int64(stmt, index, getal.boolValue ? 1 : 0)
        }
        if !CFNumberIsFloatType(getal) {
            return sqlite3_bind_int64(stmt, index, getal.int64Value)
        }
        let waarde = getal.doubleValue
        guard waarde.isFinite else {
            throw SQLiteFout("INVALID_VALUE", "Ongeldige waarde voor parameter \(index): \(waarde)", database: naam)
        }
        //JavaScript kent geen verschil tussen 5 en 5.0: gehele getallen worden INTEGER
        if waarde.rounded() == waarde && abs(waarde) <= Double(maxVeiligGetal) {
            return sqlite3_bind_int64(stmt, index, Int64(waarde))
        }
        return sqlite3_bind_double(stmt, index, waarde)
    }

    private func bindGetagd(_ stmt: OpaquePointer, _ index: Int32, _ object: [String: Any]) throws -> Int32 {
        if object.count == 1, let base64 = object["$blob"] as? String, let data = Data(base64Encoded: base64) {
            if data.isEmpty {
                return sqlite3_bind_zeroblob(stmt, index, 0)
            }
            return data.withUnsafeBytes { sqlite3_bind_blob(stmt, index, $0.baseAddress, Int32($0.count), SQLITE_TRANSIENT) }
        }
        if object.count == 1, let tekst = object["$int"] as? String, let waarde = Int64(tekst) {
            return sqlite3_bind_int64(stmt, index, waarde)
        }
        throw SQLiteFout("INVALID_VALUE", "Ongeldige waarde voor parameter \(index)", database: naam)
    }

    private func stappen(_ db: OpaquePointer, _ stmt: OpaquePointer, _ resultaat: SQLiteResultaat) throws -> [[String: Any]] {
        let aantal = sqlite3_column_count(stmt)
        let namen = (0..<aantal).map { kolom in sqlite3_column_name(stmt, kolom).map { String(cString: $0) } ?? String(kolom) }
        var rijen: [[String: Any]] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE {
                return rijen
            }
            guard rc == SQLITE_ROW else {
                throw sqlFout(rc, db)
            }
            if resultaat == .all || (resultaat == .first && rijen.isEmpty) {
                var rij = [String: Any](minimumCapacity: Int(aantal))
                for kolom in 0..<aantal {
                    rij[namen[Int(kolom)]] = waarde(stmt, kolom)
                }
                rijen.append(rij)
            }
        }
    }

    private func waarde(_ stmt: OpaquePointer, _ kolom: Int32) -> Any {
        switch sqlite3_column_type(stmt, kolom) {
        case SQLITE_INTEGER:
            let getal = sqlite3_column_int64(stmt, kolom)
            return (-maxVeiligGetal...maxVeiligGetal).contains(getal) ? NSNumber(value: getal) : ["$int": String(getal)]
        case SQLITE_FLOAT:
            let getal = sqlite3_column_double(stmt, kolom)
            return getal.isFinite ? NSNumber(value: getal) : NSNull()//JSON kent geen Infinity
        case SQLITE_TEXT:
            guard let tekst = sqlite3_column_text(stmt, kolom) else {
                return ""
            }
            let lengte = Int(sqlite3_column_bytes(stmt, kolom))
            return String(decoding: UnsafeBufferPointer(start: tekst, count: lengte), as: UTF8.self)
        case SQLITE_BLOB:
            let bytes = sqlite3_column_blob(stmt, kolom)
            let lengte = Int(sqlite3_column_bytes(stmt, kolom))
            let data = bytes.map { Data(bytes: $0, count: lengte) } ?? Data()
            return ["$blob": data.base64EncodedString()]
        default:
            return NSNull()
        }
    }
}
