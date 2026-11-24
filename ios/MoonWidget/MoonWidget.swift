import SwiftUI
import WidgetKit

// The home-screen widget: the cores, the positions and the day's trades, as the Android one shows
// them, in the lock screen's neon frame — red while any core has no connection. The app writes the
// state (WidgetStore); the widget only draws it. A tap opens the app.

/// `WidgetState` of the shared Kotlin code, as JSON.
struct WidgetData {
    struct Kind {
        let kind: String
        let trades: Int
        let wins: Int
        let losses: Int
    }

    let connected: Bool
    let online: Int
    let total: Int
    let offline: [String]
    let positions: Int
    let trades: Int
    let wins: Int
    let losses: Int
    let kinds: [Kind]
    let updated: Date?

    var alarm: Bool { !connected || !offline.isEmpty || online < total }

    init?(json: String) {
        guard
            let data = json.data(using: .utf8),
            let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        func int(_ key: String, _ from: [String: Any]) -> Int { (from[key] as? NSNumber)?.intValue ?? 0 }
        connected = (o["connected"] as? Bool) ?? false
        online = int("online", o)
        total = int("total", o)
        offline = (o["offline"] as? [String]) ?? []
        positions = int("positions", o)
        trades = int("trades", o)
        wins = int("wins", o)
        losses = int("losses", o)
        kinds = ((o["kinds"] as? [[String: Any]]) ?? []).map {
            Kind(kind: ($0["kind"] as? String) ?? "", trades: int("trades", $0), wins: int("wins", $0), losses: int("losses", $0))
        }
        let ms = (o["updated"] as? NSNumber)?.doubleValue ?? 0
        updated = ms > 0 ? Date(timeIntervalSince1970: ms / 1000) : nil
    }

    init(
        connected: Bool, online: Int, total: Int, offline: [String], positions: Int,
        trades: Int, wins: Int, losses: Int, kinds: [Kind], updated: Date?
    ) {
        self.connected = connected
        self.online = online
        self.total = total
        self.offline = offline
        self.positions = positions
        self.trades = trades
        self.wins = wins
        self.losses = losses
        self.kinds = kinds
        self.updated = updated
    }

    /// For the widget gallery.
    static let sample = WidgetData(
        connected: true, online: 3, total: 3, offline: [], positions: 2,
        trades: 14, wins: 11, losses: 3,
        kinds: [Kind(kind: "Drops", trades: 8, wins: 7, losses: 1), Kind(kind: "Spread", trades: 6, wins: 4, losses: 2)],
        updated: Date()
    )
}

struct MoonEntry: TimelineEntry {
    let date: Date
    let data: WidgetData?
    /// Why there is nothing to show, when there is not.
    let note: String
}

struct MoonProvider: TimelineProvider {
    func placeholder(in context: Context) -> MoonEntry {
        MoonEntry(date: Date(), data: .sample, note: "")
    }

    func getSnapshot(in context: Context, completion: @escaping (MoonEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : entry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MoonEntry>) -> Void) {
        // The app redraws the widget whenever the state changes; this is only a fallback.
        completion(Timeline(entries: [entry()], policy: .after(Date().addingTimeInterval(15 * 60))))
    }

    private func entry() -> MoonEntry {
        let kept = WidgetStore.load()
        if let json = kept.json, let data = WidgetData(json: json) {
            return MoonEntry(date: Date(), data: data, note: "")
        }
        return MoonEntry(date: Date(), data: nil, note: WidgetStore.describe())
    }
}

private enum Ink {
    static let text = Color.white
    static let dim = Color.white.opacity(0.7)
    static let green = Color(red: 0x4F / 255, green: 0xC3 / 255, blue: 0x8A / 255)
    static let red = Color(red: 0xE5 / 255, green: 0x64 / 255, blue: 0x5F / 255)

    /// The logo's neon, clockwise from three o'clock.
    static let neon: [Color] = [0x3DFF6E, 0x2CF5D2, 0x52FF3A, 0xFFE23D, 0xFF4B1E, 0xFF1735, 0xFF2D6A, 0x3BE7FF, 0x3DFF6E]
        .map { Color(red: Double(($0 >> 16) & 0xFF) / 255, green: Double(($0 >> 8) & 0xFF) / 255, blue: Double($0 & 0xFF) / 255) }
}

/// Black, framed: the logo's neon, or red on an alarm.
struct FrameBackground: View {
    let alarm: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous).inset(by: 3)
        let paint: AnyShapeStyle = alarm
            ? AnyShapeStyle(Ink.red)
            : AnyShapeStyle(AngularGradient(colors: Ink.neon, center: .center))
        ZStack {
            Color.black
            shape.stroke(paint, lineWidth: 6).opacity(0.3).blur(radius: 4)
            shape.stroke(paint, lineWidth: 2)
        }
    }
}

struct MoonWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: MoonEntry

    private var wide: Bool { family != .systemSmall }

    /// `14:05`, whatever the phone's 12- or 24-hour setting.
    private static let clock: DateFormatter = {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "HH:mm"
        return format
    }()

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .modifier(WidgetChrome(alarm: entry.data?.alarm ?? true))
    }

    @ViewBuilder private var content: some View {
        if let data = entry.data {
            VStack(alignment: .leading, spacing: wide ? 5 : 4) {
                HStack {
                    Text("MoonMobile").font(.system(size: 12, weight: .semibold)).foregroundColor(Ink.dim)
                    Spacer()
                    if let updated = data.updated {
                        Text(Self.clock.string(from: updated))
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundColor(Ink.dim)
                    }
                }
                cores(data).font(.system(size: wide ? 15 : 14, weight: .semibold)).lineLimit(2)
                if data.connected {
                    Text(data.positions > 0 ? "Позиции: есть (\(data.positions))" : "Позиций нет")
                        .font(.system(size: 13))
                        .foregroundColor(Ink.text)
                }
                trades(data).font(.system(size: 13)).lineLimit(1)
                kinds(data).font(.system(size: 12)).foregroundColor(Ink.dim).lineLimit(wide ? 2 : 3)
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text("MoonMobile").font(.system(size: 12, weight: .semibold)).foregroundColor(Ink.dim)
                Text("Откройте приложение и подключитесь к ядрам")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Ink.text)
                Text(entry.note).font(.system(size: 10)).foregroundColor(Ink.dim).lineLimit(3)
            }
        }
    }

    private func cores(_ data: WidgetData) -> Text {
        if !data.connected { return Text("Нет подключения к ядрам").foregroundColor(Ink.red) }
        if !data.offline.isEmpty {
            return Text(wide ? "Нет связи: \(data.offline.joined(separator: ", "))" : "Ядра \(data.online)/\(data.total)")
                .foregroundColor(Ink.red)
        }
        return Text("Ядра \(data.online)/\(data.total) на связи").foregroundColor(Ink.text)
    }

    /// "Сделки за сутки: 14  +11 −3", the plus green and the minus red.
    private func trades(_ data: WidgetData) -> Text {
        var line = Text(wide ? "Сделки за сутки: \(data.trades)" : "Сделки: \(data.trades)").foregroundColor(Ink.text)
        if data.trades > 0 {
            line = line + Text("  ") + Text("+\(data.wins)").foregroundColor(Ink.green) + Text(" ")
                + Text("−\(data.losses)").foregroundColor(Ink.red)
        }
        return line
    }

    /// "Drops 8 (+7 −1) · Spread 4 (+3 −1)"; the small one without the split.
    private func kinds(_ data: WidgetData) -> Text {
        var line = Text("")
        for (at, kind) in data.kinds.filter({ $0.trades > 0 }).enumerated() {
            if at > 0 { line = line + Text(" · ") }
            line = line + Text("\(kind.kind) \(kind.trades)")
            if wide {
                line = line + Text(" (") + Text("+\(kind.wins)").foregroundColor(Ink.green) + Text(" ")
                    + Text("−\(kind.losses)").foregroundColor(Ink.red) + Text(")")
            }
        }
        return line
    }
}

/// The frame behind the widget, edge to edge: iOS 17's container background, or a plain one before.
struct WidgetChrome: ViewModifier {
    let alarm: Bool

    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            content
                .padding(4)
                .containerBackground(for: .widget) { FrameBackground(alarm: alarm) }
        } else {
            content
                .padding(16)
                .background(FrameBackground(alarm: alarm))
        }
    }
}

@main
struct MoonWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MoonMobileWidget", provider: MoonProvider()) { entry in
            MoonWidgetView(entry: entry)
        }
        .configurationDisplayName("MoonMobile")
        .description("Ядра, позиции и сделки за сутки.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
