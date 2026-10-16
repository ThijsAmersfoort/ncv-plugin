import Foundation
import Capacitor

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(NCVpluginPlugin)
public class NCVpluginPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NCVpluginPlugin"
    public let jsName = "NCVplugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ClipboardRead", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ClipboardWrite", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sqliteOpen", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sqliteExecute", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sqliteBatch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sqliteClose", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sqliteDelete", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureGet", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureSet", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureRemove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureKeys", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureClear", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "secureMigrate", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = NCVplugin()
    private let sqlite = SQLiteService.gedeeld

    // MARK: SQLite (de SQL-logica staat in SQLiteService en SQLiteVerbinding)

    @objc func sqliteOpen(_ call: CAPPluginCall) {
        guard let name = call.getString("name"), let mode = call.getString("mode") else {
            return ongeldig(call, "name en mode zijn verplicht")
        }
        sqlite.open(naam: name, modus: mode, seed: call.getString("seedAsset")) { self.afronden(call, $0) }
    }

    @objc func sqliteExecute(_ call: CAPPluginCall) {
        guard let id = call.getString("databaseId"), let sql = call.getString("sql"),
              let resultaat = SQLiteResultaat(rawValue: call.getString("result") ?? "none") else {
            return ongeldig(call, "databaseId, sql en result zijn verplicht")
        }
        let waarden = call.options["values"] as? [Any] ?? []
        sqlite.uitvoeren(id: id, sql: sql, waarden: waarden, resultaat: resultaat) { uitkomst in
            self.afronden(call, uitkomst.map { resultaat == .none ? [:] : ["rows": $0] })
        }
    }

    @objc func sqliteBatch(_ call: CAPPluginCall) {
        guard let id = call.getString("databaseId"), let lijst = call.options["statements"] as? [[String: Any]],
              let resultaat = SQLiteResultaat(rawValue: call.getString("result") ?? "none"), resultaat != .first else {
            return ongeldig(call, "databaseId, statements en result zijn verplicht")
        }
        var statements: [(sql: String, waarden: [Any])] = []
        for item in lijst {
            guard let sql = item["sql"] as? String else {
                return ongeldig(call, "Elk statement heeft sql nodig")
            }
            statements.append((sql, item["values"] as? [Any] ?? []))
        }
        sqlite.batch(id: id, statements: statements, resultaat: resultaat) { uitkomst in
            self.afronden(call, uitkomst.map { resultaat == .none ? [:] : ["results": $0] })
        }
    }

    @objc func sqliteClose(_ call: CAPPluginCall) {
        guard let id = call.getString("databaseId") else {
            return ongeldig(call, "databaseId is verplicht")
        }
        sqlite.sluiten(id: id) { self.afronden(call, $0) }
    }

    @objc func sqliteDelete(_ call: CAPPluginCall) {
        guard let name = call.getString("name") else {
            return ongeldig(call, "name is verplicht")
        }
        sqlite.verwijderen(naam: name) { self.afronden(call, $0) }
    }

    private func ongeldig(_ call: CAPPluginCall, _ melding: String) {
        call.reject(melding, "INVALID_ARGUMENT")
    }

    private func afronden(_ call: CAPPluginCall, _ uitkomst: Result<[String: Any], Error>) {
        switch uitkomst {
        case .success(let data):
            call.resolve(data)
        case .failure(let fout):
            let sqliteFout = fout as? SQLiteFout ?? SQLiteFout("SQLITE_ERROR", fout.localizedDescription)
            call.reject(sqliteFout.message, sqliteFout.code, nil, sqliteFout.data)
        }
    }

    @objc func info(_ call: CAPPluginCall) {
        var platform = "ios"
        if ProcessInfo.processInfo.isiOSAppOnMac {
            platform = "mac"
        }
        let version = ProcessInfo.processInfo.operatingSystemVersionString
        call.resolve([
            "platform": platform,
            "version": version
        ])
    }

    @objc func ClipboardRead(_ call: CAPPluginCall) {
        let result = implementation.ClipboardRead()

        if !result.isEmpty {
            call.resolve(result)
        } else {
            call.reject("There is no data on the clipboard")
        }
    }

    @objc func ClipboardWrite(_ call: CAPPluginCall) {
        var result: Result<Void, Error>

        if let html = call.options["html"] as? String {
            result = implementation.ClipboardWrite(content: html, ofType: NCVplugin.ContentType.html)
        } else if let string = call.options["string"] as? String {
            result = implementation.ClipboardWrite(content: string, ofType: NCVplugin.ContentType.string)
        } else if let urlString = call.options["url"] as? String {
            result = implementation.ClipboardWrite(content: urlString, ofType: NCVplugin.ContentType.url)
        } else if let imageBase64 = call.options["image"] as? String {
            result = implementation.ClipboardWrite(content: imageBase64, ofType: NCVplugin.ContentType.image)
        } else {
            call.reject("No content provided")
            return
        }

        switch result {
        case .success:
            call.resolve()
        case .failure(let err):
            CAPLog.print(err.localizedDescription)
            call.reject(err.localizedDescription)
        }
    }

    @objc func openUrl(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("Must supply a URL")
            return
        }

        guard let url = URL.init(string: urlString) else {
            call.reject("Invalid URL")
            return
        }

        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { (completed) in
                call.resolve([
                    "completed": completed
                ])
            }
        }
    }

    // MARK: Beveiligde opslag (de Keychain-logica staat in SecureStorage)

    @objc func secureGet(_ call: CAPPluginCall) {
        guard let key = call.getString("key") else {
            return call.reject("key is verplicht", "INVALID_ARGUMENT")
        }
        secure(call) {
            //ontbrekende sleutel: geen value
            try SecureStorage.get(key).map { ["value": $0] } ?? [:]
        }
    }

    @objc func secureSet(_ call: CAPPluginCall) {
        guard let key = call.getString("key"), let value = call.getString("value") else {
            return call.reject("key en value zijn verplicht", "INVALID_ARGUMENT")
        }
        secure(call) {
            try SecureStorage.set(key, value)
            return [:]
        }
    }

    @objc func secureRemove(_ call: CAPPluginCall) {
        guard let key = call.getString("key") else {
            return call.reject("key is verplicht", "INVALID_ARGUMENT")
        }
        secure(call) {
            try SecureStorage.remove(key)
            return [:]
        }
    }

    @objc func secureKeys(_ call: CAPPluginCall) {
        secure(call) { ["keys": try SecureStorage.keys()] }
    }

    @objc func secureClear(_ call: CAPPluginCall) {
        secure(call) {
            try SecureStorage.clear()
            return [:]
        }
    }

    @objc func secureMigrate(_ call: CAPPluginCall) {
        //Apple gebruikt dezelfde Keychain-items als de oude plugin: niets over te zetten
        call.resolve(["migrated": [String](), "failed": [String: String]()])
    }

    private func secure(_ call: CAPPluginCall, _ actie: () throws -> [String: Any]) {
        do {
            call.resolve(try actie())
        } catch let fout as SecureStorage.Fout {
            call.reject(fout.message, fout.code)
        } catch {
            call.reject(error.localizedDescription, "OS_ERROR")
        }
    }
}
