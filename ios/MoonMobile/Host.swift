import AVFoundation
import Foundation
import LocalAuthentication
import MoonMobileKit
import Security
import UIKit
import UniformTypeIdentifiers
import UserNotifications
import WidgetKit

/// What the shared Kotlin code asks of iOS (`IosHost` in iosMain): the Rust bridge, the
/// keychain, notifications, detect sounds, Face ID, the document picker and the share sheet.
final class Host: NSObject, IosHost {
    static let shared = Host()

    private var notificationsOn = false
    private var player: AVAudioPlayer?
    private var picker: DocumentPickerDelegate?

    func start() {
        // Detect sounds keep to the ring/silent switch, as iOS users expect.
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            self.notificationsOn = granted
        }
    }

    // MARK: Bridge

    func bridgeOpen(request: String) -> String { RustBridge.open(request) }
    func bridgeCall(request: String) -> String { RustBridge.call(request) }
    func bridgeSnapshot(since: Int64) -> String { RustBridge.snapshot(since: since) }
    func bridgeTool(request: String) -> String { RustBridge.tool(request) }
    func bridgeClose() { RustBridge.close() }
    func bridgeIsOpen() -> Bool { RustBridge.isOpen }
    func bridgeVersion() -> String { RustBridge.version() }

    func bridgeChart(request: String, pixels: Int64, length: Int64) -> String {
        guard let buffer = UnsafeMutablePointer<UInt8>(bitPattern: Int(pixels)) else {
            return #"{"ok":false,"error":"bad_request","message":"no pixels"}"#
        }
        return RustBridge.chart(request, pixels: buffer, length: Int(length))
    }

    // MARK: Keychain

    private func query(_ name: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.moonmobile.secrets",
            kSecAttrAccount as String: name,
        ]
    }

    func keychainGet(name: String) -> String? {
        var item = query(name)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func keychainSet(name: String, value: String?) -> Bool {
        SecItemDelete(query(name) as CFDictionary)
        guard let value = value else { return true }
        var item = query(name)
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    // MARK: Notifications and sounds

    func notificationsAllowed() -> Bool { notificationsOn }

    /// One notification per kind of notice, replaced as its list grows; iOS stacks them by kind.
    func notify(kind: String, title: String, body: String, quiet: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.threadIdentifier = kind
        if !quiet { content.sound = .default }
        let request = UNNotificationRequest(identifier: "moon.\(kind)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Moonbot's eighteen, in its own order: a strategy may name one by its number.
    private let builtIn = [
        "Alarm", "BABYTOY", "BARK", "cork", "ERROR", "HALLO", "PFIFF", "Ringin", "ringout",
        "TurnOn", "YES_MAST", "ding1", "ding2", "Fatality", "gold", "milord", "LetsRock", "ComeGetSome",
    ]

    func playSound(name: String) {
        var wanted = name.trimmingCharacters(in: .whitespaces)
        if let dot = wanted.lastIndex(of: ".") { wanted = String(wanted[..<dot]) }
        if let number = Int(wanted), (1...builtIn.count).contains(number) { wanted = builtIn[number - 1] }
        let files = Bundle.main.paths(forResourcesOfType: nil, inDirectory: "sounds")
        let path = files.first { ($0 as NSString).lastPathComponent.lowercased().hasPrefix(wanted.lowercased() + ".") }
            ?? files.first { ($0 as NSString).lastPathComponent.lowercased().hasPrefix("ding1.") }
        guard let path = path else { return }
        DispatchQueue.main.async {
            self.player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
            self.player?.play()
        }
    }

    // MARK: Face ID

    func biometricsAvailable() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    func askBiometrics(reason: String, done: @escaping (KotlinBoolean) -> Void) {
        LAContext().evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { ok, _ in
            DispatchQueue.main.async { done(KotlinBoolean(value: ok)) }
        }
    }

    // MARK: Orientation

    /// What the app may turn to now: anything, or landscape while the chart is shown.
    static var orientations: UIInterfaceOrientationMask = Host.unlocked

    private static var unlocked: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }

    func setLandscapeOnly(on: Bool) {
        Host.orientations = on ? .landscape : Host.unlocked
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene
        else { return }
        for window in scene.windows {
            window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        if on {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape))
        }
    }

    // MARK: Home-screen widget

    private let widgetLock = NSLock()
    private var widgetsPlaced = false
    private var widgetsAskedAt = Date.distantPast
    private var widgetChannel: WidgetStore.Channel?

    func widgetShow(json: String) {
        let channel = WidgetStore.save(json)
        widgetLock.lock()
        widgetChannel = channel
        widgetLock.unlock()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// The last answer iOS gave; asked again every half minute, the answer coming a moment later.
    func widgetPlaced() -> Bool {
        widgetLock.lock()
        let ask = Date().timeIntervalSince(widgetsAskedAt) > 30
        if ask { widgetsAskedAt = Date() }
        let placed = widgetsPlaced
        widgetLock.unlock()
        if ask {
            WidgetCenter.shared.getCurrentConfigurations { result in
                let any = ((try? result.get()) ?? []).isEmpty == false
                self.widgetLock.lock()
                self.widgetsPlaced = any
                self.widgetLock.unlock()
            }
        }
        return placed
    }

    func widgetStatus() -> String {
        widgetLock.lock()
        let channel = widgetChannel
        widgetLock.unlock()
        let used = channel.map { "передаётся через: \($0.rawValue)" } ?? "ещё не передавались"
        return "\(used) · \(WidgetStore.describe())"
    }

    // MARK: Files

    private func top() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive } as? UIWindowScene
        var controller = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let next = controller?.presentedViewController { controller = next }
        return controller
    }

    func shareFiles(paths: [String], title: String) {
        DispatchQueue.main.async {
            let urls = paths.map { URL(fileURLWithPath: $0) }
            let sheet = UIActivityViewController(activityItems: urls, applicationActivities: nil)
            if let controller = self.top() {
                sheet.popoverPresentationController?.sourceView = controller.view
                controller.present(sheet, animated: true)
            }
        }
    }

    func pickDocument(done: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
            let delegate = DocumentPickerDelegate { url in
                done(url?.path)
                self.picker = nil
            }
            self.picker = delegate
            picker.delegate = delegate
            self.top()?.present(picker, animated: true)
        }
    }
}

extension Host: UNUserNotificationCenterDelegate {
    /// The app posts nothing while it is open; one that arrives as it opens shows only in the list.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.list])
    }
}

/// The picked file arrives as a copy in the app's own temporary folder.
final class DocumentPickerDelegate: NSObject, UIDocumentPickerDelegate {
    private let done: (URL?) -> Void

    init(done: @escaping (URL?) -> Void) {
        self.done = done
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        done(urls.first)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done(nil)
    }
}
