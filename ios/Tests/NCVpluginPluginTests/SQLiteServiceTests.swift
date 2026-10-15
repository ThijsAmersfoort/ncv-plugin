import XCTest
import SQLite3
@testable import NCVpluginPlugin

/// Contracttests voor SQLiteService op de systeem-SQLite (draaien in de simulator).
class SQLiteServiceTests: XCTestCase {
    private var map: URL!
    private var bundel: URL!
    private var data: URL!
    private var service: SQLiteService!

    override func setUpWithError() throws {
        map = FileManager.default.temporaryDirectory.appendingPathComponent("ncv-sqlite-" + UUID().uuidString)
        bundel = map.appendingPathComponent("bundel/public")
        data = map.appendingPathComponent("Library/LocalDatabase")
        try FileManager.default.createDirectory(at: bundel, withIntermediateDirectories: true)
        service = SQLiteService(bundelMap: bundel, dataMap: data)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: map)
    }

    // MARK: hulpfuncties

    private func wacht<T>(_ start: (@escaping (Result<T, Error>) -> Void) -> Void) -> Result<T, Error> {
        let klaar = expectation(description: "SQLite")
        var uitkomst: Result<T, Error>!
        start { uitkomst = $0; klaar.fulfill() }
        wait(for: [klaar], timeout: 60)
        return uitkomst
    }

    @discardableResult
    private func open(_ naam: String, _ modus: String = "persistent", seed: String? = nil) throws -> [String: Any] {
        try wacht { self.service.open(naam: naam, modus: modus, seed: seed, klaar: $0) }.get()
    }

    private func id(_ info: [String: Any]) -> String {
        info["databaseId"] as! String
    }

    @discardableResult
    private func sql(_ id: String, _ sql: String, _ waarden: [Any] = [], _ resultaat: SQLiteResultaat = .all) throws -> [[String: Any]] {
        try wacht { self.service.uitvoeren(id: id, sql: sql, waarden: waarden, resultaat: resultaat, klaar: $0) }.get()
    }

    @discardableResult
    private func batch(_ id: String, _ statements: [(sql: String, waarden: [Any])], _ resultaat: SQLiteResultaat = .none) throws -> [[[String: Any]]] {
        try wacht { self.service.batch(id: id, statements: statements, resultaat: resultaat, klaar: $0) }.get()
    }

    private func sluit(_ id: String) throws {
        _ = try wacht { self.service.sluiten(id: id, klaar: $0) }.get()
    }

    private func verwijder(_ naam: String) throws {
        _ = try wacht { self.service.verwijderen(naam: naam, klaar: $0) }.get()
    }

    private func fout(_ blok: () throws -> Void) -> SQLiteFout? {
        do {
            try blok()
            return nil
        } catch {
            return error as? SQLiteFout
        }
    }

    /// Maakt een database met sqlite3 zelf, als bundel- of seedbestand.
    private func maakBestand(_ url: URL, _ statements: [String]) {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        for statement in statements {
            XCTAssertEqual(sqlite3_exec(db, statement, nil, nil, nil), SQLITE_OK, statement)
        }
        sqlite3_close(db)
    }

    // MARK: tests

    func testFTS5EnBm25() throws {
        let db = id(try open("fts.sqlite"))
        try batch(db, [
            ("CREATE VIRTUAL TABLE t USING fts5(tekst)", []),
            ("INSERT INTO t(tekst) VALUES (?), (?)", ["In het begin schiep God", "Het Woord was bij God"])
        ])
        let rijen = try sql(db, "SELECT tekst, bm25(t) AS score FROM t WHERE t MATCH ? ORDER BY rank", ["woord"])
        XCTAssertEqual(rijen.count, 1)
        XCTAssertEqual(rijen.first?["tekst"] as? String, "Het Woord was bij God")
        XCTAssertNotNil(rijen.first?["score"] as? NSNumber)
    }

    func testDatatypen() throws {
        let db = id(try open("typen.sqlite"))
        try sql(db, "CREATE TABLE t (a, b, c, d, e, f, g, h, i)")
        let groot = "9223372036854775807"
        let blob = Data([0, 1, 255]).base64EncodedString()
        try sql(db, "INSERT INTO t VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [
            NSNull(), "", 0, false, 1.5, "ëmoji 😀 עִבְרִית", "a\u{0}b", ["$blob": blob], ["$int": groot]
        ])
        let rij = try XCTUnwrap(try sql(db, "SELECT *, typeof(c) AS tc, typeof(d) AS td, length(CAST(g AS BLOB)) AS lg FROM t", [], .first).first)
        XCTAssertTrue(rij["a"] is NSNull)
        XCTAssertEqual(rij["b"] as? String, "")
        XCTAssertEqual(rij["tc"] as? String, "integer")
        XCTAssertEqual(rij["td"] as? String, "integer")
        XCTAssertEqual(rij["e"] as? Double, 1.5)
        XCTAssertEqual(rij["f"] as? String, "ëmoji 😀 עִבְרִית")
        XCTAssertEqual(rij["g"] as? String, "a\u{0}b")
        XCTAssertEqual(rij["lg"] as? Int, 3)
        XCTAssertEqual(rij["h"] as? [String: String], ["$blob": blob])
        XCTAssertEqual(rij["i"] as? [String: String], ["$int": groot])

        //een geheel getal als double (zo komt een JavaScript-getal binnen) wordt INTEGER
        let soort = try sql(db, "SELECT typeof(?) AS soort", [NSNumber(value: 5.0)], .first)
        XCTAssertEqual(soort.first?["soort"] as? String, "integer")
        XCTAssertEqual(fout { try self.sql(db, "SELECT ?", [Double.nan]) }?.code, "INVALID_VALUE")
        XCTAssertEqual(fout { try self.sql(db, "SELECT ?", [["x": 1]]) }?.code, "INVALID_VALUE")
    }

    func testResultaatvormen() throws {
        let db = id(try open("vorm.sqlite"))
        try sql(db, "CREATE TABLE t (x)")
        XCTAssertEqual(try sql(db, "SELECT * FROM t", [], .first).count, 0)
        XCTAssertEqual(try sql(db, "SELECT * FROM t").count, 0)
        try batch(db, [("INSERT INTO t VALUES (1)", []), ("INSERT INTO t VALUES (2)", [])])
        XCTAssertEqual(try sql(db, "SELECT x FROM t ORDER BY x", [], .first).first?["x"] as? Int, 1)
        XCTAssertEqual(try sql(db, "INSERT INTO t VALUES (3)", [], .none).count, 0)
        let uitkomst = try batch(db, [("SELECT count(*) AS n FROM t", []), ("SELECT x FROM t WHERE x > ?", [5])], .all)
        XCTAssertEqual(uitkomst.count, 2)
        XCTAssertEqual(uitkomst[0].first?["n"] as? Int, 3)
        XCTAssertEqual(uitkomst[1].count, 0)
        //alleen witruimte of commentaar
        XCTAssertEqual(try sql(db, "  -- niets").count, 0)
    }

    func testBatchRollbackEnFoutindex() throws {
        let db = id(try open("rollback.sqlite"))
        try sql(db, "CREATE TABLE t (x UNIQUE)")
        let f = fout {
            try self.batch(db, [("INSERT INTO t VALUES (1)", []), ("INSERT INTO t VALUES (1)", [])])
        }
        XCTAssertEqual(f?.code, "SQL_ERROR")
        XCTAssertEqual(f?.statementIndex, 1)
        XCTAssertEqual(f?.sqliteCode.map { $0 & 0xff }, SQLITE_CONSTRAINT)
        XCTAssertEqual(try sql(db, "SELECT count(*) AS n FROM t").first?["n"] as? Int, 0)
        //de verbinding blijft bruikbaar
        try batch(db, [("INSERT INTO t VALUES (2)", [])])
        XCTAssertEqual(try sql(db, "SELECT count(*) AS n FROM t").first?["n"] as? Int, 1)
    }

    func testTransactiecontroleEnMeerdereStatements() throws {
        let db = id(try open("controle.sqlite"))
        try sql(db, "CREATE TABLE t (x)")
        XCTAssertEqual(fout { try self.batch(db, [("INSERT INTO t VALUES (1)", []), ("COMMIT", [])]) }?.code, "TRANSACTION_CONTROL")
        XCTAssertEqual(fout { try self.batch(db, [("INSERT INTO t VALUES (1)", []), (" /* x */ -- y\n commit", [])]) }?.code, "TRANSACTION_CONTROL")
        XCTAssertEqual(fout { try self.batch(db, [("BEGIN", [])]) }?.code, "TRANSACTION_CONTROL")
        XCTAssertEqual(fout { try self.batch(db, [("SAVEPOINT a", [])]) }?.statementIndex, 0)
        XCTAssertFalse(SQLiteVerbinding.isTransactiecontrole("CREATE TRIGGER x AFTER INSERT ON t BEGIN SELECT 1; END"))
        XCTAssertTrue(SQLiteVerbinding.isTransactiecontrole("\u{FEFF}COMMIT"))
        XCTAssertEqual(try sql(db, ";").count, 0)
        XCTAssertEqual(try sql(db, "SELECT count(*) AS n FROM t").first?["n"] as? Int, 0)
        XCTAssertEqual(fout { try self.sql(db, "INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)") }?.code, "MULTIPLE_STATEMENTS")
        XCTAssertEqual(try sql(db, "SELECT count(*) AS n FROM t").first?["n"] as? Int, 0)
        //afsluitende puntkomma en commentaar zijn geen tweede statement
        try sql(db, "INSERT INTO t VALUES (3); -- klaar")
        try sql(db, "VACUUM")
        XCTAssertEqual(try sql(db, "SELECT count(*) AS n FROM t").first?["n"] as? Int, 1)
    }

    func testNotitieTriggersMetPuntkommas() throws {
        let db = id(try open("notes.sqlite"))
        try batch(db, [
            ("CREATE TABLE notes (idate integer NOT NULL, vid integer, title varchar(500), 'text' varchar(8000))", []),
            ("CREATE VIRTUAL TABLE notesFTS USING fts5(text,title,content=notes)", []),
            ("""
            CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
                INSERT INTO notesFTS(rowid, "text", title) VALUES (new.rowid, new.text, new.title);
            END
            """, []),
            ("""
            CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
                INSERT INTO notesFTS(notesFTS, rowid, "text", title) VALUES('delete', old.rowid, old.text, old.title);
                INSERT INTO notesFTS(rowid, "text", title) VALUES (new.rowid, new.text, new.title);
            END
            """, []),
            ("PRAGMA user_version = 2", [])
        ])
        try sql(db, "INSERT INTO notes (idate, vid, title, text) VALUES (?, ?, ?, ?)", [1_700_000_000_000, 49001001, "Titel", "genade en vrede"])
        XCTAssertEqual(try sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'genade'").count, 1)
        //fout in statement 2: ook de FTS-wijziging van statement 1 gaat terug
        XCTAssertNotNil(fout {
            try self.batch(db, [("UPDATE notes SET text = 'liefde'", []), ("INSERT INTO bestaatniet VALUES (1)", [])])
        })
        XCTAssertEqual(try sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'genade'").count, 1)
        XCTAssertEqual(try sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'liefde'").count, 0)
        XCTAssertEqual(try sql(db, "PRAGMA user_version", [], .first).first?["user_version"] as? Int, 2)
    }

    func testSeedEenmaligEnWeesJournal() throws {
        maakBestand(bundel.appendingPathComponent("user.sqlite"), ["CREATE TABLE t (x)", "INSERT INTO t VALUES ('seed')"])
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        //een achtergebleven journal van een verwijderde database mag niet op de seed worden toegepast
        try Data("oud".utf8).write(to: data.appendingPathComponent("user.sqlite-journal"))

        let eerste = try open("user.sqlite", seed: "user.sqlite")
        XCTAssertEqual(eerste["copied"] as? Bool, true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: data.appendingPathComponent("user.sqlite-journal").path))
        let db = id(eerste)
        try sql(db, "INSERT INTO t VALUES ('gebruiker')")
        try sluit(db)

        let tweede = try open("user.sqlite", seed: "user.sqlite")
        XCTAssertEqual(tweede["copied"] as? Bool, false)
        XCTAssertEqual(try sql(id(tweede), "SELECT count(*) AS n FROM t").first?["n"] as? Int, 2)
        let backup = try data.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(backup.isExcludedFromBackup, true)
    }

    func testOntbrekendeSeedMaaktGeenLegeDatabase() throws {
        XCTAssertEqual(fout { try self.open("user.sqlite", seed: "user.sqlite") }?.code, "ASSET_NOT_FOUND")
        XCTAssertFalse(FileManager.default.fileExists(atPath: data.appendingPathComponent("user.sqlite").path))
        XCTAssertEqual(fout { try self.open("NCVbasis.sqlite", "bundled") }?.code, "ASSET_NOT_FOUND")
        XCTAssertEqual(fout { try self.open("../user.sqlite") }?.code, "INVALID_ARGUMENT")
        XCTAssertEqual(fout { try self.open("x.sqlite", "anders") }?.code, "INVALID_ARGUMENT")
    }

    func testBundelAlleenLezenEnHergebruik() throws {
        maakBestand(bundel.appendingPathComponent("basis.sqlite"), ["CREATE TABLE t (x)", "INSERT INTO t VALUES (1)", "PRAGMA user_version = 14"])
        let info = try open("basis.sqlite", "bundled")
        XCTAssertEqual(info["readOnly"] as? Bool, true)
        XCTAssertEqual(info["source"] as? String, "bundle")
        XCTAssertEqual(info["userVersion"] as? Int, 14)
        let db = id(info)
        XCTAssertEqual(fout { try self.sql(db, "INSERT INTO t VALUES (2)") }?.sqliteCode.map { $0 & 0xff }, SQLITE_READONLY)
        XCTAssertNotNil(fout { try self.batch(db, [("UPDATE t SET x = 3", [])]) })
        XCTAssertNotNil(fout { try self.sql(db, "PRAGMA user_version = 15") })
        XCTAssertEqual(try sql(db, "SELECT x FROM t").first?["x"] as? Int, 1)

        //tweede opening (WebView herladen): dezelfde verbinding
        let opnieuw = try open("basis.sqlite", "bundled")
        XCTAssertEqual(opnieuw["reused"] as? Bool, true)
        XCTAssertEqual(id(opnieuw), db)
    }

    func testSluitenEnVerwijderen() throws {
        let db = id(try open("weg.sqlite"))
        try sql(db, "CREATE TABLE t (x)")
        XCTAssertEqual(fout { try self.verwijder("weg.sqlite") }?.code, "DB_IN_USE")

        //werk dat vóór het sluiten is aangeboden, loopt nog af
        let klaar = expectation(description: "insert")
        service.batch(id: db, statements: [("INSERT INTO t VALUES (1)", [])], resultaat: .none) { uitkomst in
            if case .failure = uitkomst { XCTFail("insert vóór sluiten mislukt") }
            klaar.fulfill()
        }
        try sluit(db)
        wait(for: [klaar], timeout: 10)
        XCTAssertEqual(fout { try self.sql(db, "SELECT 1") }?.code, "DB_CLOSED")
        XCTAssertEqual(fout { try self.sluit(db) }?.code, "DB_CLOSED")

        //twee objecten voor dezelfde verbinding (zoals na een herlaad): na het sluiten weigert ook de andere direct
        let tweede = id(try open("weg2.sqlite"))
        XCTAssertEqual(id(try open("weg2.sqlite")), tweede)
        let sluitKlaar = expectation(description: "sluiten")
        service.sluiten(id: tweede) { _ in sluitKlaar.fulfill() }
        XCTAssertEqual(fout { try self.sql(tweede, "SELECT 1") }?.code, "DB_CLOSED")
        wait(for: [sluitKlaar], timeout: 10)

        try verwijder("weg.sqlite")
        XCTAssertFalse(FileManager.default.fileExists(atPath: data.appendingPathComponent("weg.sqlite").path))
        try verwijder("weg.sqlite")//ontbrekend bestand is geen fout
    }

    /// De echte databases uit de app, als de app-map naast de plugin staat.
    func testAppDatabases() throws {
        var publicMap = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 {
            publicMap.deleteLastPathComponent()
        }
        publicMap.appendPathComponent("NCV Bijbelstudie/public")
        guard FileManager.default.fileExists(atPath: publicMap.appendingPathComponent("NCVbasis.sqlite").path) else {
            throw XCTSkip("app-map niet gevonden: \(publicMap.path)")
        }
        let app = SQLiteService(bundelMap: publicMap, dataMap: data)
        let basis = id(try wacht { app.open(naam: "NCVbasis.sqlite", modus: "bundled", seed: nil, klaar: $0) }.get())
        for tabel in ["COMfts", "NCVkopjesFTS", "VOETNOTENfts"] {
            let rijen = try wacht {
                app.uitvoeren(id: basis, sql: "SELECT rowid, bm25(\(tabel)) AS score FROM \(tabel) WHERE \(tabel) MATCH 'God' ORDER BY rank LIMIT 3", waarden: [], resultaat: .all, klaar: $0)
            }.get()
            XCTAssertFalse(rijen.isEmpty, tabel)
        }
        let user = try wacht { app.open(naam: "user.sqlite", modus: "persistent", seed: "user.sqlite", klaar: $0) }.get()
        XCTAssertEqual(user["copied"] as? Bool, true)
        let userId = id(user)
        _ = try wacht {
            app.batch(id: userId, statements: [
                ("INSERT INTO notes (idate, vid, title, text, bewerkt, sync) VALUES (?, ?, ?, ?, ?, 0)", [1, 43001001, "Test", "genadeproef", 1])
            ], resultaat: .none, klaar: $0)
        }.get()
        let treffers = try wacht {
            app.uitvoeren(id: userId, sql: "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'genadeproef'", waarden: [], resultaat: .all, klaar: $0)
        }.get()
        XCTAssertEqual(treffers.count, 1)
        let controle = try wacht { app.uitvoeren(id: userId, sql: "PRAGMA quick_check", waarden: [], resultaat: .first, klaar: $0) }.get()
        XCTAssertEqual(controle.first?["quick_check"] as? String, "ok")
    }
}
