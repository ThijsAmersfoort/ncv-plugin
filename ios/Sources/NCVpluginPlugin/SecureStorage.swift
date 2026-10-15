import Foundation
import Security

/// Beveiligde opslag (inloggegevens) in de Keychain.
///
/// Gebruikt precies de items van de vroegere cordova-plugin-secure-storage-echo (SAMKeychain):
/// generic password, service `my_app`, account = sleutel, data = UTF-8 van de string, geen eigen access group.
/// Bestaande waarden blijven daardoor zonder migratie bereikbaar, ook in de iOS-app op de Mac.
enum SecureStorage {
    static let service = "my_app"

    struct Fout: Error {
        let code: String
        let message: String
    }

    /// Zoekquery voor één sleutel, of voor alle sleutels van de service.
    /// SAMKeychain zocht met kSecAttrSynchronizableAny: eventuele iCloud-varianten worden zo ook gevonden en gewist.
    /// kSecUseDataProtectionKeychain doet niets op iOS en in de iOS-app op de Mac; een Mac Catalyst-build gebruikt
    /// daarmee dezelfde soort Keychain in plaats van de oude macOS-bestandskeychain.
    private static func query(_ key: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecUseDataProtectionKeychain as String: true
        ]
        if let key = key {
            query[kSecAttrAccount as String] = key
        }
        return query
    }

    private static func fout(_ status: OSStatus, _ actie: String) -> Fout {
        //errSecInteractionNotAllowed: apparaat vergrendeld, het item is nu niet leesbaar (maar bestaat wel)
        let code = status == errSecInteractionNotAllowed ? "LOCKED" : "OS_ERROR"
        let omschrijving = (SecCopyErrorMessageString(status, nil) as String?) ?? ""
        return Fout(code: code, message: "\(actie) mislukt (\(status)) \(omschrijving)")
    }

    /// nil als de sleutel niet bestaat
    static func get(_ key: String) throws -> String? {
        var query = query(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw fout(status, "Lezen")
        }
        guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
            throw Fout(code: "INVALID_RECORD", message: "Opgeslagen waarde is geen UTF-8-tekst")
        }
        return value
    }

    static func set(_ key: String, _ value: String) throws {
        let data = Data(value.utf8)
        let status = SecItemUpdate(query(key) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            //nieuw item: niet gesynchroniseerd en de systeemstandaard WhenUnlocked, zoals de oude plugin ze aanmaakte
            var item = query(key)
            item.removeValue(forKey: kSecAttrSynchronizable as String)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlocked
            item[kSecValueData as String] = data
            let addStatus = SecItemAdd(item as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw fout(addStatus, "Opslaan")
            }
        } else if status != errSecSuccess {
            throw fout(status, "Opslaan")
        }
    }

    static func remove(_ key: String) throws {
        let status = SecItemDelete(query(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw fout(status, "Verwijderen")
        }
    }

    static func keys() throws -> [String] {
        var query = query(nil)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return []
        }
        guard status == errSecSuccess else {
            throw fout(status, "Sleutels lezen")
        }
        let items = result as? [[String: Any]] ?? []
        return Array(Set(items.compactMap { $0[kSecAttrAccount as String] as? String })).sorted()
    }

    /// Wist alleen de items van service my_app, niet de rest van de Keychain van de app
    static func clear() throws {
        let status = SecItemDelete(query(nil) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw fout(status, "Wissen")
        }
    }
}
