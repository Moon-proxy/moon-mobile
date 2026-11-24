package app.moonmobile.core

import app.moonmobile.platform.Ios
import app.moonmobile.platform.PlatformContext as Context

/**
 * The widget lives in the app's WidgetKit extension (ios/MoonWidget), drawn in SwiftUI. The app
 * hands it the state as JSON through the Swift host, which keeps it where the extension reads it.
 */
actual object HomeWidget {
    actual fun show(context: Context, state: WidgetState) {
        Ios.host.widgetShow(state.toJson().toString())
    }

    actual fun placed(context: Context): Boolean = Ios.host.widgetPlaced()

    actual val howToPlace: String =
        "На iPad и iPhone: долгое нажатие на экране «Домой» → «Изменить» → «Добавить виджет» → " +
            "MoonMobile, маленький или средний. Рамка красная, если хоть одно ядро без связи; " +
            "нажатие открывает приложение. iOS не даёт приложению работать в фоне, поэтому виджет " +
            "обновляется, пока приложение открыто, и показывает время последнего обновления."

    actual fun status(): String? = "Передача данных виджету: ${Ios.host.widgetStatus()}"
}
