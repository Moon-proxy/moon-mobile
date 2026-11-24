import Foundation
import Security

/// How the app hands the home-screen widget its picture: the state as JSON (`WidgetState` in the
/// shared Kotlin code), kept where the widget's extension can read it.
///
/// The usual place is an App Group's folder. A free Apple ID signing on the owner's PC may not
/// grant one, or grant it under a name of its own, so the groups this very signature was given are
/// read from the bundle's provisioning profile (and from `ALTAppGroups`, where AltStore and
/// SideStore leave the renamed one). Failing a group, the state goes to a keychain item in the
/// team's shared access group, which a development profile grants to every app of the team.
/// The same file is compiled into the app and into the widget.
enum WidgetStore {
    /// The group asked for at build time; the signature may rename it.
    static let configuredGroup = "group.app.moonmobile"
    private static let fileName = "widget.json"
    private static let keychainService = "app.moonmobile.widget"
    private static let keychainAccount = "state"

    /// Where the state goes; how it went, for the settings and the widget to tell.
    enum Channel: String {
        case group = "общая папка App Group"
        case keychain = "связка ключей команды"
        case none = "нет"
    }

    // MARK: The signature

    /// The bundle's provisioning profile, as signed in by Sideloadly, AltStore or Xcode.
    private static let profile: [String: Any]? = {
        // A signed (CMS) envelope round an XML property list: the list is taken out by its bytes.
        guard
            let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            let data = try? Data(contentsOf: url),
            let start = data.range(of: Data("<?xml".utf8)),
            let end = data.range(of: Data("</plist>".utf8), in: start.upperBound..<data.endIndex)
        else { return nil }
        let plist = data.subdata(in: start.lowerBound..<end.upperBound)
        return (try? PropertyListSerialization.propertyList(from: plist, format: nil)) as? [String: Any]
    }()

    private static var entitlements: [String: Any] { profile?["Entitlements"] as? [String: Any] ?? [:] }

    /// The App Groups to try, the ones the signature names first.
    static var groups: [String] {
        var found: [String] = []
        if let alt = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] { found += alt }
        found += (entitlements["com.apple.security.application-groups"] as? [String]) ?? []
        found.append(configuredGroup)
        var seen = Set<String>()
        return found.filter { !$0.isEmpty && !$0.contains("*") && seen.insert($0).inserted }
    }

    /// The team the bundle is signed for, as its keychain groups are prefixed.
    static var team: String? {
        if let prefix = (profile?["ApplicationIdentifierPrefix"] as? [String])?.first { return prefix }
        if let team = entitlements["com.apple.developer.team-identifier"] as? String { return team }
        if let id = entitlements["application-identifier"] as? String, let dot = id.firstIndex(of: ".") {
            return String(id[..<dot])
        }
        return nil
    }

    private static var keychainGroup: String? { team.map { "\($0).app.moonmobile.widget" } }

    // MARK: Group folder

    private static func groupFile() -> URL? {
        for group in groups {
            if let folder = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) {
                return folder.appendingPathComponent(fileName)
            }
        }
        return nil
    }

    // MARK: Keychain

    private static func keychainQuery() -> [String: Any]? {
        guard let group = keychainGroup else { return nil }
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessGroup as String: group,
        ]
    }

    // MARK: The app writes

    /// Keeps [json] for the widget; how it went.
    @discardableResult
    static func save(_ json: String) -> Channel {
        let data = Data(json.utf8)
        if let file = groupFile(), (try? data.write(to: file, options: .atomic)) != nil {
            return .group
        }
        if var item = keychainQuery() {
            SecItemDelete(item as CFDictionary)
            item[kSecValueData as String] = data
            // The widget is drawn with the phone locked too.
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            if SecItemAdd(item as CFDictionary, nil) == errSecSuccess { return .keychain }
        }
        return .none
    }

    // MARK: The widget reads

    /// The kept state and where it came from; nil with the reason it could not be had.
    static func load() -> (json: String?, channel: Channel) {
        if let file = groupFile(), let data = try? Data(contentsOf: file), let json = String(data: data, encoding: .utf8) {
            return (json, .group)
        }
        if var item = keychainQuery() {
            item[kSecReturnData as String] = true
            item[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            if SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess,
               let data = result as? Data, let json = String(data: data, encoding: .utf8) {
                return (json, .keychain)
            }
        }
        return (nil, .none)
    }

    /// What the signature allows, in a line for the settings.
    static func describe() -> String {
        let named = (entitlements["com.apple.security.application-groups"] as? [String]) ?? []
        let folder = groupFile() != nil
        var parts: [String] = []
        parts.append(folder ? "App Group: есть" : (named.isEmpty ? "App Group: подпись не выдала" : "App Group: недоступна"))
        parts.append(team.map { "команда \($0)" } ?? "профиль подписи не найден")
        return parts.joined(separator: " · ")
    }
}
