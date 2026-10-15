import XCTest
import Security
@testable import NCVpluginPlugin

/// Draai op een iOS-simulator: de Keychain van macOS-tests werkt anders.
final class SecureStorageTests: XCTestCase {

    override func setUpWithError() throws {
        try SecureStorage.clear()
    }

    override func tearDownWithError() throws {
        try SecureStorage.clear()
    }

    /// Schrijft zoals SAMKeychainQuery.save in cordova-plugin-secure-storage-echo 5.1.1 (service my_app):
    /// zoeken met kSecAttrSynchronizableAny, dan bijwerken of toevoegen met diezelfde query.
    @discardableResult
    private func oudePluginSet(_ account: String, _ password: String, service: String = "my_app") -> OSStatus {
        let zoek: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny
        ]
        let data = password.data(using: .utf8)!
        var status = SecItemCopyMatching(zoek as CFDictionary, nil)
        if status == errSecSuccess {
            status = SecItemUpdate(zoek as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        } else if status == errSecItemNotFound {
            var item = zoek
            item[kSecValueData as String] = data
            status = SecItemAdd(item as CFDictionary, nil)
        }
        return status
    }

    private func aantalItems(_ account: String) -> Int {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "my_app",
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return 0 }
        return (result as? [[String: Any]])?.count ?? 0
    }

    func testLeestWaardenVanDeOudePlugin() throws {
        let waarden = [
            "email": "test@example.com",
            "pw": "Wachtwoord-€😀-0123 ",
            "p": "12345",
            "leeg": "",
            "lang": String(repeating: "x", count: 300) + "€"
        ]
        for (key, value) in waarden {
            XCTAssertEqual(oudePluginSet(key, value), errSecSuccess)
        }
        for (key, value) in waarden {
            XCTAssertEqual(try SecureStorage.get(key), value, key)
        }
        XCTAssertEqual(try SecureStorage.keys(), waarden.keys.sorted())
    }

    func testOntbrekendeSleutelIsNil() throws {
        XCTAssertNil(try SecureStorage.get("bestaat-niet"))
    }

    func testSetWerktHetOudeItemBij() throws {
        oudePluginSet("pw", "oud")
        try SecureStorage.set("pw", "nieuw")
        XCTAssertEqual(try SecureStorage.get("pw"), "nieuw")
        XCTAssertEqual(aantalItems("pw"), 1)
    }

    func testNieuweWaardenBlijvenExactGelijk() throws {
        for value in ["0123", "2026-10-15", "true", "", "ëmoji 😀\n"] {
            try SecureStorage.set("w", value)
            XCTAssertEqual(try SecureStorage.get("w"), value)
        }
    }

    func testRemoveEnClear() throws {
        oudePluginSet("email", "a@b.nl")
        try SecureStorage.set("pw", "geheim")
        try SecureStorage.remove("email")
        try SecureStorage.remove("email") //ontbrekend is geen fout
        XCTAssertNil(try SecureStorage.get("email"))
        XCTAssertEqual(try SecureStorage.get("pw"), "geheim")
        try SecureStorage.clear()
        XCTAssertEqual(try SecureStorage.keys(), [])
    }

    func testClearLaatAndereServicesStaan() throws {
        XCTAssertEqual(oudePluginSet("x", "blijft", service: "ander"), errSecSuccess)
        try SecureStorage.set("pw", "weg")
        try SecureStorage.clear()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "ander",
            kSecAttrAccount as String: "x"
        ]
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, nil), errSecSuccess)
        SecItemDelete(query as CFDictionary)
    }
}
