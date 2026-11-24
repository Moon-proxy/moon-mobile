import Foundation
import SwiftUI
import UIKit

/// Why the app closed last time, for the next launch to show. The shared Kotlin code writes its
/// uncaught exceptions to Documents/crash.txt (CrashLog.kt); this side records Objective-C
/// exceptions and fatal signals (a Swift trap, a bad memory access, an abort) next to it.
enum Crash {
    private static let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    private static let files = ["crash.txt", "crash-objc.txt", "crash-signal.txt"].map { folder.appendingPathComponent($0) }
    fileprivate static let objcFile = files[1]
    fileprivate static let signalFile = files[2]

    static func install() {
        NSSetUncaughtExceptionHandler { exception in
            let text = "Objective-C: \(exception.name.rawValue): \(exception.reason ?? "")\n\n"
                + exception.callStackSymbols.joined(separator: "\n")
            try? text.write(to: Crash.objcFile, atomically: false, encoding: .utf8)
        }
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGTRAP, SIGFPE] {
            signal(sig) { sig in Crash.onSignal(sig) }
        }
    }

    fileprivate static func onSignal(_ sig: Int32) {
        let text = "Signal \(sig)\n\n" + Thread.callStackSymbols.joined(separator: "\n")
        try? text.write(to: signalFile, atomically: false, encoding: .utf8)
        signal(sig, SIG_DFL)
        raise(sig)
    }

    /// What the last crash left, or nil when the app closed normally.
    static func lastReport() -> String? {
        let parts = files.compactMap { try? String(contentsOf: $0, encoding: .utf8) }.filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: "\n\n———\n\n")
    }

    static func clear() {
        for file in files { try? FileManager.default.removeItem(at: file) }
    }
}

/// The last crash's report, to be photographed or copied, before the app starts as usual.
struct CrashView: View {
    let report: String
    let done: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MoonMobile закрылся с ошибкой").font(.headline)
            Text("Нажми «Скопировать» и пришли текст, или сделай снимок экрана.")
                .font(.subheadline)
                .foregroundColor(.secondary)
            ScrollView {
                Text(report)
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack {
                Button("Скопировать") { UIPasteboard.general.string = report }
                Spacer()
                Button("Продолжить") { done() }
            }
        }
        .padding()
    }
}
