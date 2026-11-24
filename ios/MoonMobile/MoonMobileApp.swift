import MoonMobileKit
import SwiftUI
import UIKit
import UserNotifications

/// The iOS app: the shared Compose screens (android/app/src/commonMain) in a SwiftUI window,
/// with `Host` handing them the Rust bridge and the iOS services.
@main
struct MoonMobileApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        Crash.install()
        CrashLogKt.installCrashLog()
        Host.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                // The screen stays on while the app is open, as a video player's does.
                UIApplication.shared.isIdleTimerDisabled = true
                MainViewControllerKt.appCameBack()
            case .background:
                UIApplication.shared.isIdleTimerDisabled = false
                MainViewControllerKt.appWentAway()
            default: break
            }
        }
    }
}

/// Only for the orientations: the chart holds the app in landscape (Host.setLandscapeOnly).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        Host.orientations
    }
}

/// The last crash's report when there is one, then the shared screens.
struct RootView: View {
    @State private var report = Crash.lastReport()

    var body: some View {
        if let text = report {
            CrashView(report: text) {
                Crash.clear()
                report = nil
            }
        } else {
            ComposeScreen().ignoresSafeArea(.keyboard)
        }
    }
}

/// The shared screens as one view controller.
struct ComposeScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(host: Host.shared)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
