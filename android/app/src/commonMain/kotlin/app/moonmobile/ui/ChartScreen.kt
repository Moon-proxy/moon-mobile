package app.moonmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.AppState
import app.moonmobile.core.Card
import app.moonmobile.core.ChartPrefs
import app.moonmobile.core.ChartSettings
import app.moonmobile.core.Core
import app.moonmobile.core.Native
import app.moonmobile.core.Session
import app.moonmobile.core.errorReply
import app.moonmobile.core.parseReply
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.moonmobile.json.JSONArray
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import app.moonmobile.core.Snapshot
import app.moonmobile.core.LastDeal
import app.moonmobile.core.Order
import app.moonmobile.json.JSONObject
import app.moonmobile.text.fmt
import app.moonmobile.platform.PlatformContext as Context
import app.moonmobile.text.nowMs
import androidx.compose.ui.graphics.ImageBitmap
import app.moonmobile.text.localOffsetMs
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.pow

/** The narrowest and the widest window a pinch or the wheel can reach. */
private const val MIN_SPAN_MS = 10_000L
private const val MAX_SPAN_MS = 12L * 3_600_000L

/**
 * How often the picture is drawn while it follows the present (about 25 frames a second, so the
 * trades and the scale move smoothly), and while it stands in the past with nothing touched.
 */
private const val FRAME_LIVE_MS = 33L
private const val FRAME_IDLE_MS = 250L

/** Two taps this close in time are a double tap. */
private const val DOUBLE_TAP_MS = 320L

/** After the picture is let go, this long passes before it centres itself on the spread again. */
private const val CENTER_HOLD_MS = 5_000L

/** After an order line is let go, this long passes before the picture moves again. */
private const val LINE_DROP_HOLD_MS = 3_000L

/** How long a picked take % shows its levels on the chart. */
private const val TAKE_FLASH_MS = 500L

/**
 * How long a newly opened chart waits for its market's hour range before it takes the scale
 * from what it shows instead (openScale).
 */
private const val OPEN_FIT_MS = 6_000L

/** The narrowest scale a chart opens at, percent. */
private const val OPEN_SCALE_MIN = 10.0

/**
 * The fixed scale a newly opened chart takes: how far the price ranged over the last hour, in
 * percent of the price, as it is — 20% opens at 20%, 50% at 50% — and never under 10%.
 */
internal fun openScale(hourRange: Double): Double =
    if (hourRange.isFinite()) maxOf(OPEN_SCALE_MIN, ceil(hourRange * 10.0) / 10.0) else OPEN_SCALE_MIN

/** A mouse dragged this many pixels with the right button doubles or halves the price range. */
private const val SCALE_DRAG_PX = 160f

/** A finger held this long on an own line over the book picks the line up. */
private const val LINE_HOLD_MS = 350L

/** How long the chart's note stays. */
private const val NOTE_MS = 2_500L

/** The leverages the menu offers, cut at the market's own maximum. */
private val leverageSteps = listOf(1, 2, 3, 5, 10, 15, 20, 25, 50, 75, 100, 125, 150, 200)

/** The terminal's chart colours, for the controls that sit on the picture, in the theme. */
private val chartBackground get() = Palette.chart
private val chartLabel get() = Palette.chartLabel
private val panelBackground get() = Palette.chartPanel
private val cardBackground get() = Palette.chartCard
private val terminalGreen get() = Palette.terminalGreen
private val terminalRed get() = Palette.terminalRed

/**
 * The time window one chart shows, the last picture drawn of it and what that picture's reply
 * said about it. The gestures change the window; the drawing loop reads it and asks the bridge
 * for the frame.
 */
private class ChartView(span: Long) {
    var spanMs by mutableLongStateOf(span.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS))

    /** A take % just picked, shown on the chart until [takeFlashUntil] (ms). */
    var takeFlashPct = 0.0
    var takeFlashUntil = 0L

    /** Right edge of the window; `null` follows the present. */
    var endMs by mutableStateOf<Long?>(null)

    /** Where the plot lies inside the last picture, to turn a finger into a time and a price. */
    var plotX by mutableFloatStateOf(0f)
    var plotY by mutableFloatStateOf(0f)
    var plotW by mutableFloatStateOf(1f)
    var plotH by mutableFloatStateOf(1f)
    var priceLo by mutableStateOf(0.0)
    var priceHi by mutableStateOf(0.0)

    /** Where the volume band and the order book begin, in the picture's pixels; 0 before it. */
    var bandTop by mutableFloatStateOf(0f)
    var bookX by mutableFloatStateOf(0f)

    /** The scale the last picture used, as a percentage of the price. */
    var scaleNow by mutableStateOf(0.0)

    /** The scale asked for: 0 fits the range; a pinch changes it live. */
    var scalePct by mutableStateOf(0.0)

    /** How far down the picture's top-left captions reach, in its pixels. */
    var captionsBottom by mutableFloatStateOf(0f)

    /** Pixels at the plot's left edge under the Long button, kept clear in the picture. */
    var leftInset by mutableFloatStateOf(0f)

    /** `spot`, `futures`, `quarterly`, or empty while the core has not said. */
    var kind by mutableStateOf("")
    /** How far the price ranged over 3 h, 1 h and 15 min, percent; `null` while unknown. */
    var vola by mutableStateOf<List<Double?>>(listOf(null, null, null))
    var leverage by mutableStateOf<Int?>(null)
    var maxLeverage by mutableStateOf<Int?>(null)
    var isolated by mutableStateOf<Boolean?>(null)

    /** The crosshair, in the picture's pixels; `null` until it is first placed. */
    var cursor by mutableStateOf<Offset?>(null)

    var bitmap by mutableStateOf<ImageBitmap?>(null)

    /** Bumped with every picture: the bitmap object stays the same, so this is what redraws. */
    var frame by mutableIntStateOf(0)
    var problem by mutableStateOf<String?>(null)

    /** Wakes the drawing loop before its timer when a gesture moved the window. */
    val redraw = Channel<Unit>(Channel.CONFLATED)

    /** The right edge that counts as "the present": a tenth of the window stays empty. */
    private fun liveEdge(): Long = nowMs() + spanMs / 10

    /** Narrows ([factor] above 1) or widens the window, keeping the time under [focusX] in place. */
    fun zoom(factor: Float, focusX: Float) {
        if (factor.isNaN() || factor <= 0f) return
        val old = spanMs
        val span = (old / factor).toLong().coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        val end = endMs
        if (end != null) {
            val fraction = ((focusX - plotX) / plotW).coerceIn(0f, 1f)
            val anchor = end - old + (old * fraction).toLong()
            val moved = anchor + (span * (1f - fraction)).toLong()
            spanMs = span
            endMs = if (moved >= liveEdge()) null else moved
        } else {
            // Following the present: the right edge stays where it is.
            spanMs = span
        }
        redraw.trySend(Unit)
    }

    /** Slides the window by [dx] pixels: dragging to the right goes back in time. */
    fun pan(dx: Float) {
        val end = endMs
        // Nothing lies to the right of the present.
        if (end == null && dx <= 0f) return
        val edge = liveEdge()
        val moved = (end ?: edge) - (dx / plotW * spanMs).toLong()
        endMs = if (moved >= edge) null else moved
        redraw.trySend(Unit)
    }

    fun followPresent() {
        endMs = null
        center = null
        redraw.trySend(Unit)
    }

    /**
     * The price held in the middle while the picture is dragged and for [CENTER_HOLD_MS] after
     * it is let go; `null` lets the chart centre itself on the spread.
     */
    var center by mutableStateOf<Double?>(null)

    /** A finger or a mouse button is on the picture. */
    @Volatile
    var touching = false

    /** When the picture was let go. */
    @Volatile
    var releasedAt = 0L

    /** How long after being let go the picture stays where it was left. */
    @Volatile
    var holdMs = CENTER_HOLD_MS

    /**
     * The automatic scale's range frozen while an order line is carried, as a percentage; 0 when
     * nothing is frozen. The picture must not stretch under a line being placed.
     */
    @Volatile
    var heldScale = 0.0

    /** The order line under the finger on the book, for Join and Split: from the touch until let go. */
    @Volatile
    var selected: LineHit? = null

    /** Holds the middle where it is for as long as the picture is held. */
    fun hold() {
        touching = true
        holdMs = CENTER_HOLD_MS
        if (center == null && priceHi > priceLo) center = (priceLo + priceHi) / 2.0
    }

    /**
     * An order line is being carried: the picture stands still — no centring, no fitting —
     * until [LINE_DROP_HOLD_MS] after the line is let go.
     */
    fun holdForLine() {
        touching = true
        holdMs = LINE_DROP_HOLD_MS
        if (center == null && priceHi > priceLo) center = (priceLo + priceHi) / 2.0
        if (scalePct <= 0.0 && scaleNow > 0.0) heldScale = scaleNow
    }

    fun letGo() {
        touching = false
        releasedAt = nowMs()
    }

    /** Moves the picture [dy] pixels down: higher prices come into view from the top. */
    fun nudge(dy: Float) {
        val held = center ?: return
        if (dy == 0f || !(priceHi > priceLo) || plotH <= 0f) return
        center = held + dy / plotH * (priceHi - priceLo)
        redraw.trySend(Unit)
    }

    /** The price at height [y] of the picture, or `null` off the plot or before a picture. */
    fun priceAt(y: Float): Double? {
        if (!(priceHi > priceLo) || y < plotY || y > plotY + plotH) return null
        return priceHi - (y - plotY) / plotH * (priceHi - priceLo)
    }

    /** Whether [x] lies over the order book: the one zone an order is placed from. */
    fun inBook(x: Float): Boolean = bookX > 0f && x >= bookX

    val futures: Boolean get() = kind != "spot"

    /** The own order lines of the last picture, with where they were drawn. */
    var lines by mutableStateOf<List<LineHit>>(emptyList())

    /** A line being dragged and where the finger holds it now. */
    var drag by mutableStateOf<Dragged?>(null)

    /**
     * A line let go at a new price, drawn there until the core moves the order (or a few seconds
     * pass): without it the line would jump back for a moment.
     */
    var pendingMove: PendingMove? = null

    /** The small note over the chart: a hint or a refusal, gone after a few seconds. */
    var note by mutableStateOf<Note?>(null)

    fun say(text: String, error: Boolean = false) {
        note = Note(text, error, nowMs())
    }

    /** The line within [reach] pixels of height [y], nearest first; stops and takes win ties. */
    fun lineAt(y: Float, reach: Float): LineHit? =
        lines.filter { it.role != "filled" && abs(it.y - y) <= reach }.minByOrNull { abs(it.y - y) }

    /** The price at height [y], held to the plot: a finger dragged off it keeps the edge price. */
    fun priceHeld(y: Float): Double? = priceAt(y.coerceIn(plotY, plotY + plotH))
}

/** One own order line on the picture: which order and level it is, and where it lies. */
private data class LineHit(val uid: String, val role: String, val price: Double, val y: Float, val short: Boolean)

/** A dragged line: the line and the height the finger holds it at. */
private data class Dragged(val line: LineHit, val y: Float)

/** A dropped line waiting for its order to move: drawn at [price] until [untilMs]. */
private data class PendingMove(val uid: String, val role: String, val price: Double, val untilMs: Long)

/** How long a dropped line is held at its new price if the core does not move the order. */
private const val PENDING_MOVE_MS = 4_000L

private data class Note(val text: String, val error: Boolean, val at: Long)

/** A preset of the top bar being retyped. */
private data class PresetEdit(val sizes: Boolean, val index: Int)

/**
 * The chart, full screen and in landscape, laid out like the terminal's: the top bar with the
 * order-size and take-profit presets, the leverage and the price scale; the picture the bridge
 * draws with the trading buttons on it; and the detect cards to its right under the market's
 * name. With no market chosen yet it opens on the market box.
 */
@Composable
internal fun ChartScreen(
    state: AppState,
    core: Long,
    market: String,
    snackbar: SnackbarHostState,
    onPick: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    LandscapeWhileShown()

    val context = platformContext()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snapshot = state.snapshot
    val cores = remember(snapshot?.cores) { snapshot?.cores.orEmpty().filter { it.active } }
    val coreInfo = cores.firstOrNull { it.id == core }
    val coreName = coreInfo?.name ?: ""
    val chosen = market.isNotEmpty() && coreInfo != null
    // The demo core's chart is drawn by the bridge itself and needs no market data.
    val available = state.demo || (snapshot != null && snapshot.marketData != "none")
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pickerCores by remember { mutableStateOf(AppPrefs.pickerCores(context)) }
    var choosingCores by remember { mutableStateOf(false) }
    val barScroll = rememberScrollState()
    // The chart's own core first, then the others in their order.
    val searched = remember(cores, pickerCores, core) {
        cores.filter { pickerCores?.contains(it.id) ?: true }.map { it.id }
            .sortedBy { if (it == core) 0 else 1 }
    }
    var settings by remember { mutableStateOf(ChartPrefs.load(context)) }
    var editing by remember { mutableStateOf<PresetEdit?>(null) }
    // An open tab keeps its scale across switching between the tabs; a chart opened anew takes
    // its scale from what it shows (openScale), and leaving the chart forgets them all.
    val view = remember(core, market) {
        ChartView(settings.spanMs).also { it.scalePct = ChartSession.scales["$core:$market"] ?: 0.0 }
    }
    LaunchedEffect(view) {
        snapshotFlow { view.scalePct }.collect { ChartSession.scales["$core:$market"] = it }
    }
    LaunchedEffect(view) {
        if (view.scalePct > 0.0) return@LaunchedEffect
        // The scale of the market's last hour, once it is known: at once if it already is, else
        // the chart opens fitted to what it holds and takes it as it comes in, within a few
        // seconds. A scale the user sets meanwhile stands; with no hour by then, the widest fit
        // seen stands in for it.
        val until = nowMs() + OPEN_FIT_MS
        var widest = 0.0
        while (view.scalePct <= 0.0 && nowMs() < until) {
            val hour = view.vola.getOrNull(1)
            if (hour != null && hour > 0.0) {
                view.scalePct = openScale(hour)
                return@LaunchedEffect
            }
            widest = maxOf(widest, view.scaleNow)
            delay(100L)
        }
        if (view.scalePct <= 0.0) view.scalePct = openScale(widest)
    }
    DisposableEffect(Unit) {
        onDispose { ChartSession.scales.clear() }
    }
    var showPositions by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    // П is as wide as the back button above it: measured, as the arrow's width is the font's.
    var backWidthPx by remember { mutableIntStateOf(0) }
    val layoutDensity = LocalDensity.current
    val positionsWidth = if (backWidthPx > 0) with(layoutDensity) { backWidthPx.toDp() } else 38.dp
    val tabHeight = chartTabHeight(isTablet())
    // Down over the chart's funding row while a chart is shown.
    val positionsReach = if (chosen && !picking) FUNDING_ROW_HEIGHT else 0.dp
    // What the chart's core has free for this market, in the currency the market is margined in
    // (USDC on a USDC contract, the coin on a coin-margined one); asked every 5 seconds.
    var freeBalance by remember(core, market) { mutableStateOf<Pair<Double, String>?>(null) }
    LaunchedEffect(core, market) {
        if (market.isEmpty()) return@LaunchedEffect
        while (true) {
            val reply = Session.call(JSONObject().put("op", "free_balance").put("core", core).put("market", market))
            if (reply.ok) {
                freeBalance = reply.json.optDouble("free", 0.0) to reply.json.optString("currency")
            }
            delay(5_000L)
        }
    }
    LaunchedEffect(core, market, chosen) {
        if (chosen) ChartSession.open(core, market)
    }
    val change: (ChartSettings) -> Unit = { changed ->
        settings = changed
        ChartPrefs.save(context, changed)
        view.redraw.trySend(Unit)
    }

    // The window's width is remembered once the fingers have let it rest.
    LaunchedEffect(view, view.spanMs) {
        delay(800L)
        if (view.spanMs != settings.spanMs) change(settings.copy(spanMs = view.spanMs))
    }

    /** Sends a command; only a failure is said, in a small note on the chart. */
    val send: (JSONObject) -> Unit = { request ->
        scope.launch {
            val reply = Session.call(request)
            if (!reply.ok) view.say(Session.describeError(reply), error = true)
        }
    }

    /** Places an order at [price] with the chosen size, take profit and stop loss. */
    val placeOrder: (Boolean, Double) -> Unit = { short, price ->
        val usd = settings.sizes[settings.sizeSel]
        val take = settings.takes[settings.takeSel]
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val request = JSONObject()
                .put("op", "place_order")
                .put("core", core)
                .put("market", market)
                .put("short", short)
                .put("price", price)
                .put("usd", usd)
                .put("take_pct", take)
            if (settings.stopOn) request.put("stop_pct", settings.stopPct)
            val reply = Session.call(request)
            // The order's line on the chart is the confirmation; only a refusal is said.
            if (!reply.ok) view.say(Session.describeError(reply), error = true)
        }
    }

    /** Moves one level of an order to [price]: what dropping a dragged line does. */
    val moveLine: (LineHit, Double) -> Unit = { line, price ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        send(
            JSONObject()
                .put("op", "move_line")
                .put("core", core)
                .put("market", market)
                .put("uid", line.uid)
                .put("role", line.role)
                .put("price", price),
        )
    }

    /**
     * Join and Split, on the take held on the book: every sell of its side into one at their
     * average, or that sell in two at its price.
     */
    val joinOrSplit: (Boolean) -> Unit = { join ->
        val held = view.selected?.takeIf { it.role == "exit" }
        if (held == null) {
            view.say("Удерживайте тейк на стакане и нажмите ${if (join) "Join" else "Split"}")
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                val request = if (join) {
                    JSONObject().put("op", "join_sells").put("core", core).put("market", market).put("short", held.short)
                } else {
                    JSONObject().put("op", "split_order").put("core", core).put("uid", held.uid)
                }
                val reply = Session.call(request)
                if (!reply.ok) {
                    view.say(
                        if (reply.code == "nothing_to_join") "Тейк один — объединять нечего" else Session.describeError(reply),
                        error = true,
                    )
                }
            }
            view.selected = null
        }
    }

    /** The Take button: the next exit, or the waiting entries, to [price]. */
    val takeTo: (Double, Int) -> Unit = { price, step ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val reply = Session.call(
                JSONObject()
                    .put("op", "take_to")
                    .put("core", core)
                    .put("market", market)
                    .put("price", price)
                    .put("step", step),
            )
            if (!reply.ok) {
                view.say(
                    when (reply.code) {
                        "mixed_sides" -> "Ордера Long и Short вместе — Take их не двигает"
                        "nothing_to_move" -> "Нет ордеров, которые можно переставить"
                        else -> Session.describeError(reply)
                    },
                    error = true,
                )
            }
        }
    }

    BackHandler {
        if (picking && chosen) picking = false else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chartBackground)
            .safeDrawingPaddingScaled(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOP_BAR_HEIGHT)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = if (available && (picking || !chosen)) {
                        Modifier
                    } else {
                        Modifier
                            .weight(1f)
                            .horizontalScroll(barScroll)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BackBarButton(
                        onClick = { if (picking && chosen) picking = false else onBack() },
                        modifier = Modifier.onSizeChanged { backWidthPx = it.width },
                    )
                    if (available && !(picking || !chosen)) {
                        BarButton(
                            "$market ▾",
                            selected = false,
                            onClick = { picking = true },
                        )
                    }
                    if (available && chosen && !picking) {
                        Spacer(Modifier.width(4.dp))
                        settings.sizes.forEachIndexed { index, size ->
                            PresetButton(
                                label = sizeLabel(size),
                                selected = index == settings.sizeSel,
                                tint = terminalGreen,
                                onSelect = { change(settings.copy(sizeSel = index)) },
                                onEdit = { editing = PresetEdit(sizes = true, index = index) },
                            )
                        }
                        if (view.futures) {
                            LeverageButton(view) { leverage ->
                                send(
                                    JSONObject()
                                        .put("op", "set_leverage")
                                        .put("core", core)
                                        .put("market", market)
                                        .put("leverage", leverage),
                                )
                            }
                        } else {
                            Spacer(Modifier.width(4.dp))
                        }
                        settings.takes.forEachIndexed { index, take ->
                            PresetButton(
                                label = "${trimNumber(take)}%",
                                selected = index == settings.takeSel,
                                tint = Color(0xFF7FC9FF),
                                onSelect = {
                                    change(settings.copy(takeSel = index))
                                    // Where this take would sit, for half a second.
                                    view.takeFlashPct = take
                                    view.takeFlashUntil = nowMs() + TAKE_FLASH_MS
                                    view.redraw.trySend(Unit)
                                    scope.launch {
                                        delay(TAKE_FLASH_MS + 40L)
                                        view.redraw.trySend(Unit)
                                    }
                                },
                                onEdit = { editing = PresetEdit(sizes = false, index = index) },
                            )
                        }
                    }
                }
                if (available && (picking || !chosen)) {
                    SearchBox(query, { query = it }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    BarButton(
                        if (pickerCores == null) "Ядра: все ▾" else "Ядра: ${searched.size} ▾",
                        onClick = { choosingCores = true },
                    )
                }
                if (available && chosen && !picking) {
                    // The chart's own tools, one size and always in view: scale, stop, mode.
                    Row(
                        modifier = Modifier.padding(start = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScaleButton(
                            scalePct = view.scalePct,
                            onChoose = {
                                view.scalePct = it
                                change(settings.copy(scalePct = it))
                            },
                        )
                        StopButton(settings = settings, onChange = change)
                        ModeButton(
                            settings = settings,
                            spot = !view.futures,
                            onChange = change,
                        )
                    }
                }
            }
            if (available) {
                TabStrip(
                    cores = cores,
                    current = if (chosen) core to market else null,
                    leading = positionsWidth,
                    onSelect = { (tabCore, tabMarket) ->
                        picking = false
                        onPick(tabCore, tabMarket)
                    },
                    onClose = { tab ->
                        val at = ChartSession.tabs.indexOf(tab)
                        ChartSession.tabs.remove(tab)
                        ChartSession.scales.remove("${tab.first}:${tab.second}")
                        if (tab == (core to market)) {
                            val next = ChartSession.tabs.getOrNull(at) ?: ChartSession.tabs.lastOrNull()
                            if (next != null) onPick(next.first, next.second) else onPick(core, "")
                        }
                    },
                    balance = freeBalance?.takeIf { chosen }?.let { (free, currency) ->
                        val amount = if (state.balanceHidden) "••••••" else plainAmount(free)
                        amount to currency
                    },
                    lastDeal = snapshot?.lastDeal,
                    onDeal = { deal ->
                        picking = false
                        onPick(deal.core, deal.market)
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    !available -> Text(
                        "Графики выключены. Отключитесь, поставьте галочку «Графики» на экране " +
                            "подключения и подключитесь заново.",
                        color = Palette.textDim,
                        modifier = Modifier.padding(16.dp),
                    )
                    picking || !chosen -> MarketPicker(
                        cores = cores,
                        searched = searched,
                        query = query,
                        onPick = { pickedCore, pickedMarket ->
                            picking = false
                            onPick(pickedCore, pickedMarket)
                        },
                    )
                    else -> Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            ChartCanvas(
                                core = core,
                                market = market,
                                view = view,
                                settings = settings,
                                onScaleChange = { change(settings.copy(scalePct = it)) },
                                light = Palette.light,
                                onBookTap = { short, price -> placeOrder(short, price) },
                                onTake = takeTo,
                                onMoveLine = moveLine,
                                onHint = { view.say(it) },
                            )
                            ChartButtons(
                                view = view,
                                settings = settings,
                                joinSplit = ChartPrefs.layers(context).collectAsState().value.joinSplit,
                                onJoin = { joinOrSplit(true) },
                                onSplit = { joinOrSplit(false) },
                                onCancelBuy = {
                                    send(
                                        JSONObject()
                                            .put("op", "cancel_market_buys")
                                            .put("core", core)
                                            .put("market", market),
                                    )
                                },
                                onPanicSell = {
                                    send(
                                        JSONObject()
                                            .put("op", "panic_market")
                                            .put("core", core)
                                            .put("market", market)
                                            .put("on", true),
                                    )
                                },
                            )
                        }
                        DetectPanel(
                            market = market,
                            exchange = coreInfo?.exchange.orEmpty(),
                            coreName = coreName,
                            vola = view.vola,
                            cards = snapshot?.cards.orEmpty(),
                            onPick = onPick,
                            modifier = Modifier
                                .width(DETECT_PANEL_WIDTH)
                                .fillMaxHeight(),
                        )
                    }
                }
                // Over the chart, not instead of it: the chart keeps running underneath. A tap
                // anywhere else on the chart closes it.
                if (showPositions && available) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { showPositions = false } },
                    )
                    PositionsPanel(
                        snapshot = snapshot,
                        cores = cores,
                        current = if (chosen) core to market else null,
                        onPick = { pickedCore, pickedMarket ->
                            picking = false
                            onPick(pickedCore, pickedMarket)
                        },
                        onCancel = { order ->
                            send(
                                JSONObject()
                                    .put("op", "cancel_order")
                                    .put("core", order.core)
                                    .put("uid", order.uid),
                            )
                        },
                        onControl = { controlled, on ->
                            send(JSONObject().put("op", "set_control").put("core", controlled.id).put("on", on))
                        },
                        onRun = { target, on ->
                            send(
                                JSONObject().put("op", "run").put("core", target.id)
                                    .put("switch", "trading").put("on", on),
                            )
                        },
                        onReport = { showReport = true },
                        onClose = { showPositions = false },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = positionsReach)
                            .width(340.dp)
                            .fillMaxHeight(),
                    )
                }
                if (showReport && available) {
                    ReportPanel(
                        cores = cores,
                        current = if (chosen) core to market else null,
                        onPick = { pickedCore, pickedMarket ->
                            picking = false
                            showPositions = false
                            onPick(pickedCore, pickedMarket)
                        },
                        onClose = { showReport = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                    )
                }
            }
        }
        // П over the left end of the tabs, as wide as the back button and reaching down over the
        // chart's first caption row, the funding: a target the thumb finds without looking.
        if (available) {
            PositionsButton(
                open = showPositions,
                onClick = { showPositions = !showPositions },
                modifier = Modifier
                    .padding(start = 4.dp, top = TOP_BAR_HEIGHT + 1.dp)
                    .size(width = positionsWidth, height = tabHeight + 1.dp + positionsReach),
            )
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (choosingCores) {
        CoreSetDialog(
            title = "Ядра для поиска рынков",
            cores = cores,
            chosen = pickerCores,
            onDismiss = { choosingCores = false },
            onSave = {
                choosingCores = false
                pickerCores = it
                AppPrefs.setPickerCores(context, it)
            },
        )
    }

    val edit = editing
    if (edit != null) {
        val current = if (edit.sizes) settings.sizes[edit.index] else settings.takes[edit.index]
        ValueDialog(
            title = if (edit.sizes) {
                "Объём ордера ${edit.index + 1}, USD"
            } else {
                "Тейк ${edit.index + 1}, % от цены входа"
            },
            hint = null,
            initial = trimNumber(current),
            limit = if (edit.sizes) 10_000_000.0 else 900.0,
            onDismiss = { editing = null },
            onSave = { typed ->
                editing = null
                change(
                    if (edit.sizes) {
                        settings.copy(
                            sizes = settings.sizes.replaced(edit.index, typed),
                            sizeSel = edit.index,
                        )
                    } else {
                        settings.copy(
                            takes = settings.takes.replaced(edit.index, (typed * 100.0).roundToLong() / 100.0),
                            takeSel = edit.index,
                        )
                    },
                )
            },
        )
    }
}

private fun List<Double>.replaced(index: Int, value: Double): List<Double> =
    mapIndexed { at, old -> if (at == index) value else old }

/** A number without a trailing `.0`, with a dot whatever the phone's locale. */
private fun trimNumber(value: Double): String {
    val decimals = if (abs(value) < 1.0) 8 else 4
    val text = fmt("%.${decimals}f", value).trimEnd('0').trimEnd('.')
    return text.ifEmpty { "0" }
}

/** An order size the way a narrow button can hold it: `50$`, `1k$`, `2.5k$`. */
private fun sizeLabel(usd: Double): String = when {
    usd >= 1_000_000.0 -> "${trimNumber(usd / 1_000_000.0)}M$"
    usd >= 1_000.0 -> "${trimNumber(usd / 1_000.0)}k$"
    else -> "${trimNumber(usd)}$"
}

/** One compact toolbar entry. */
@Composable
private fun BarButton(
    label: String,
    selected: Boolean = false,
    color: Color = chartLabel,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.surfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chart's top bar: the back button, the presets, the scale. */
private val TOP_BAR_HEIGHT = 42.dp

/** The chart's first caption row, the funding, as the bridge draws it: 15 points down and 5 spare. */
private val FUNDING_ROW_HEIGHT = 20.dp

/** The chart tabs' height: taller on a tablet, for the finger. */
private fun chartTabHeight(tablet: Boolean) = if (tablet) 30.dp else 20.dp

/**
 * П: the open positions over the chart. As wide as the back button and from the tabs' top down
 * to the chart's funding row, it lies over the left end of both.
 */
@Composable
private fun PositionsButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            // Opaque: the price scale under it must not show through.
            .background(if (open) Palette.accent.copy(alpha = 0.3f).compositeOver(Palette.chartButton) else Palette.chartButton)
            .border(1.dp, if (open) Palette.accent else Palette.chartEdge, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "П",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 18.sp,
            color = if (open) Palette.accent else chartLabel,
        )
    }
}

/** The chart's way back: a bar button outlined, and a fifth wider than the bare arrow would be. */
@Composable
private fun BackBarButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, chartLabel.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("←", style = MaterialTheme.typography.labelLarge, color = chartLabel, maxLines = 1)
    }
}

/**
 * One of the six order sizes or six take-profit percentages. A tap chooses it; holding it or
 * tapping it twice opens its value for retyping.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(
    label: String,
    selected: Boolean,
    tint: Color,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) tint else Palette.chartEdge, shape)
            .combinedClickable(onClick = onSelect, onLongClick = onEdit, onDoubleClick = onEdit)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (selected) tint else chartLabel,
            maxLines = 1,
        )
    }
}

/**
 * The market's leverage: a narrow button with the one in force, opening a table of the steps up
 * to the market's maximum under the margin type, Cross or Isolated, as the exchange reports it.
 * The margin type itself is switched in the terminal or on the exchange.
 */
@Composable
private fun LeverageButton(view: ChartView, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val max = view.maxLeverage
    Box {
        val shape = RoundedCornerShape(5.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color(0x33B97800))
                .border(1.dp, Color(0xFFB97800), shape)
                .clickable { open = true }
                .padding(horizontal = 5.dp, vertical = 6.dp),
        ) {
            Text(
                view.leverage?.let { "${it}x" } ?: "x?",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (Palette.light) Color(0xFF9A5B00) else Color(0xFFFFB347),
                maxLines = 1,
            )
        }
        MoonMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                when (view.isolated) {
                    true -> "Isolated"
                    false -> "Cross"
                    else -> "—"
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Palette.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            val steps = (leverageSteps.filter { max == null || it <= max } +
                listOfNotNull(max?.takeIf { it !in leverageSteps })).sorted()
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (row in steps.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (step in row) {
                            val current = step == view.leverage
                            val cell = RoundedCornerShape(5.dp)
                            Box(
                                modifier = Modifier
                                    .width(46.dp)
                                    .clip(cell)
                                    .background(if (current) Palette.accent.copy(alpha = 0.25f) else Palette.surfaceHigh)
                                    .border(1.dp, if (current) Palette.accent else Palette.outline, cell)
                                    .clickable {
                                        open = false
                                        onChoose(step)
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${step}x",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) Palette.accent else Palette.text,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The size every one of the chart's tool buttons has. */
private val toolWidth = 48.dp
private val toolHeight = 30.dp

/**
 * One of the chart's tools at the right of the top bar: one size and shape for all, each in its
 * own colour, so they read as a set apart from the size and take presets.
 */
@Composable
private fun ToolButton(tint: Color, lit: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = toolWidth, height = toolHeight)
            .clip(shape)
            .background(tint.copy(alpha = if (lit) 0.32f else 0.12f))
            .border(1.dp, tint.copy(alpha = if (lit) 1f else 0.75f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The scale button's mark: a price range between two arrows. */
@Composable
private fun RangeIcon(tint: Color) {
    Canvas(modifier = Modifier.size(width = 7.dp, height = 14.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.3f * density
        drawLine(tint, Offset(w / 2f, 1.5f * density), Offset(w / 2f, h - 1.5f * density), strokeWidth = stroke)
        val head = 2.6f * density
        drawLine(tint, Offset(w / 2f, 0.8f * density), Offset(w / 2f - head, head + 0.8f * density), strokeWidth = stroke)
        drawLine(tint, Offset(w / 2f, 0.8f * density), Offset(w / 2f + head, head + 0.8f * density), strokeWidth = stroke)
        drawLine(tint, Offset(w / 2f, h - 0.8f * density), Offset(w / 2f - head, h - head - 0.8f * density), strokeWidth = stroke)
        drawLine(tint, Offset(w / 2f, h - 0.8f * density), Offset(w / 2f + head, h - head - 0.8f * density), strokeWidth = stroke)
    }
}

/** The scale's colour: its own, apart from the take presets' blue. */
private val scaleTint get() = if (Palette.light) Color(0xFF6A4FB3) else Color(0xFFB9A2F0)

/** The price scale: fitted to what is on screen, or a fixed share of the price. */
@Composable
private fun ScaleButton(scalePct: Double, onChoose: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val caption: (Double) -> String = { if (it <= 0.0) "Авто" else "${trimNumber(it)}%" }
    Box {
        ToolButton(tint = scaleTint, lit = false, onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeIcon(scaleTint)
                Text(
                    if (scalePct <= 0.0) "A" else "${trimNumber((scalePct * 10.0).roundToLong() / 10.0)}%",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (scalePct >= 100.0) 9.sp else 11.sp,
                    color = scaleTint,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        MoonMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in ChartPrefs.scaleChoices) {
                DropdownMenuItem(
                    text = {
                        Text(
                            caption(choice),
                            fontWeight = if (choice == scalePct) FontWeight.Bold else FontWeight.Normal,
                            color = if (choice == scalePct) Palette.accent else Palette.text,
                        )
                    },
                    onClick = {
                        open = false
                        onChoose(choice)
                    },
                )
            }
        }
    }
}

/**
 * The stop loss for orders placed from the chart: a red S opening a slider for its distance from
 * the entry and a tick that turns it on. Strategy orders keep their own stops.
 */
@Composable
private fun StopButton(settings: ChartSettings, onChange: (ChartSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pct by remember(settings.stopPct) { mutableStateOf(settings.stopPct.toFloat()) }
    val red = terminalRed
    Box {
        ToolButton(tint = red, lit = settings.stopOn, onClick = { open = true }) {
            Text(
                "S",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (settings.stopOn && !Palette.light) Color.White else red,
                maxLines = 1,
            )
        }
        MoonMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.width(240.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChange(settings.copy(stopOn = !settings.stopOn)) },
                ) {
                    Checkbox(
                        checked = settings.stopOn,
                        onCheckedChange = { onChange(settings.copy(stopOn = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = red),
                    )
                    Text("Стоп-лосс", fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "−${trimNumber((pct * 10.0).roundToLong() / 10.0)}%",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.stopOn) red else Palette.textDim,
                    )
                }
                Slider(
                    value = pct,
                    onValueChange = { pct = ((it * 10f).roundToInt() / 10f).coerceIn(ChartPrefs.STOP_MIN.toFloat(), ChartPrefs.STOP_MAX.toFloat()) },
                    onValueChangeFinished = { onChange(settings.copy(stopPct = pct.toDouble())) },
                    valueRange = ChartPrefs.STOP_MIN.toFloat()..ChartPrefs.STOP_MAX.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = red, activeTrackColor = red),
                )
                Text(
                    "Только для ордеров, выставленных с графика. Выключено — стоп как в настройках ядра.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.textDim,
                )
            }
        }
    }
}

/**
 * Touch or cursor, in the top bar's right corner. On a spot market it also chooses whether one
 * tap on the book places the order or a double tap does.
 */
@Composable
private fun ModeButton(settings: ChartSettings, spot: Boolean, onChange: (ChartSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(tint = chartLabel, lit = false, onClick = { open = true }) {
            if (settings.cursorMode) MouseIcon(17.dp, chartLabel) else TouchIcon(18.dp, chartLabel)
        }
        MoonMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TouchIcon(16.dp, Palette.text)
                            Text(
                                " Сенсор",
                                fontWeight = if (!settings.cursorMode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        Text(
                            "Ордер: удерживайте Short или Long и нажмите на стакан.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.textDim,
                        )
                    }
                },
                onClick = {
                    open = false
                    onChange(settings.copy(cursorMode = false))
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MouseIcon(16.dp, Palette.text)
                            Text(
                                " Курсор",
                                fontWeight = if (settings.cursorMode) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        Text(
                            "Подключите мышку или используйте тачпад в качестве мышки.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.textDim,
                        )
                        Text(
                            "Мышь: левая кнопка на стакане — Long, правая — Short. Палец: двойное касание — Long у курсора, касание двумя пальцами — Short.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.textDim,
                        )
                    }
                },
                onClick = {
                    open = false
                    onChange(settings.copy(cursorMode = true))
                },
            )
            if (spot || settings.cursorMode) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            if (settings.spotSingleTap) {
                                "Ордер одним нажатием на стакан ✓ (спот и мышка)"
                            } else {
                                "Ордер двойным нажатием на стакан ✓ (спот и мышка)"
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onChange(settings.copy(spotSingleTap = !settings.spotSingleTap))
                    },
                )
            }
        }
    }
}

/** Retyping one preset: a positive number up to [limit]. */
@Composable
private fun ValueDialog(
    title: String,
    hint: String?,
    initial: String,
    limit: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val value = text.trim().replace(',', '.').toDoubleOrNull()
    val valid = value != null && value > 0.0 && value <= limit
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value != null && valid) onSave(value) }, enabled = valid) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Whether Short or Long is held down right now, and whether an order was placed during this
 * hold. Shared between the button and the chart, which separate fingers touch.
 */
private class Hold {
    @Volatile var value: Boolean = false
    @Volatile var used: Boolean = false
}

private val holdShort = Hold()
private val holdLong = Hold()

/** The Take button; [step] counts the exits moved while it is held. */
private val holdTake = Hold()

@Volatile
private var takeStep = 0

/**
 * The picture itself and the hands on it.
 *
 * Touch mode: one finger slides the window through time; two fingers stretch it — apart side
 * by side for time, one above the other for price; a double tap returns to the present. A tap
 * on the book places an order: on futures while Short or Long is held, on spot by a tap or a
 * double tap as chosen. Cursor mode: one finger moves the crosshair like a touchpad, two move
 * and stretch the chart. A mouse moves the crosshair by hovering, drags the chart and zooms
 * with its wheel.
 */
@Composable
private fun ChartCanvas(
    core: Long,
    market: String,
    view: ChartView,
    settings: ChartSettings,
    onScaleChange: (Double) -> Unit,
    light: Boolean,
    onBookTap: (Boolean, Double) -> Unit,
    onTake: (Double, Int) -> Unit,
    onMoveLine: (LineHit, Double) -> Unit,
    onHint: (String) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    // Settings → График: read on every frame, so a switch shows at once.
    val context = platformContext()
    val layers = ChartPrefs.layers(context)

    LaunchedEffect(core, market, size, settings.cursorMode, light) {
        val width = size.width
        val height = size.height
        if (width < 64 || height < 64) return@LaunchedEffect
        // The bridge draws into this picture directly; the screen shows it.
        val frame = ChartFrame(width, height)
        if (settings.cursorMode && view.cursor == null) {
            view.cursor = Offset(width * 0.6f, height * 0.4f)
        }
        while (isActive) {
            // Frames on an even beat: the wait below is what is left of the frame's time after
            // drawing it, so a slower frame does not make the next one late as well.
            val frameStart = nowMs()
            val request = JSONObject()
                .put("core", core)
                .put("market", market)
                .put("width", width)
                .put("height", height)
                .put("scale", density.toDouble())
                .put("span_ms", view.spanMs)
                .put("tz_offset_min", localOffsetMs(nowMs()) / 60_000)
                .put("book_marks", !settings.cursorMode)
                .put("light", light)
                .put("left_inset", view.leftInset.toDouble())
                .put("mark_line", layers.value.markLine)
                .put("liquidations", layers.value.liquidations)
            val end = view.endMs
            if (end != null) request.put("end_ms", end)
            val scalePct = view.scalePct.takeIf { it > 0.0 } ?: view.heldScale
            if (scalePct > 0.0) request.put("scale_pct", scalePct)
            // A few seconds after the picture is let go it centres and fits itself again.
            val holdOver = !view.touching && nowMs() - view.releasedAt > view.holdMs
            if (view.center != null && holdOver) view.center = null
            if (view.heldScale > 0.0 && (holdOver || view.scalePct > 0.0)) view.heldScale = 0.0
            view.center?.let { request.put("center", it) }
            if (nowMs() < view.takeFlashUntil) request.put("take_flash_pct", view.takeFlashPct)
            request.put("session_from_ms", ChartPrefs.sessionStartMs(context, nowMs()))
            val cursor = view.cursor
            if (settings.cursorMode && cursor != null) {
                request.put("cursor", JSONArray().put(cursor.x.toDouble()).put(cursor.y.toDouble()))
            }
            // The line under the finger is drawn by the bridge where the finger holds it, in its
            // own colour and with its caption reckoned for that price; once let go, it stays at
            // the new price until the core has moved the order.
            val dragged = view.drag
            val heldPrice = dragged?.let { view.priceHeld(it.y) }
            val pending = view.pendingMove?.takeIf { nowMs() < it.untilMs }
            if (pending == null) view.pendingMove = null
            when {
                dragged != null && heldPrice != null -> request.put(
                    "drag",
                    JSONObject().put("uid", dragged.line.uid).put("role", dragged.line.role).put("price", heldPrice),
                )
                pending != null -> request.put(
                    "drag",
                    JSONObject().put("uid", pending.uid).put("role", pending.role).put("price", pending.price),
                )
            }
            val text = request.toString()
            val reply = withContext(Dispatchers.Default) {
                try {
                    parseReply(frame.draw(text))
                } catch (e: Throwable) {
                    errorReply("internal", e.toString())
                }
            }
            if (reply.ok) {
                val json = reply.json
                val plot = json.optJSONObject("plot")
                if (plot != null) {
                    view.plotX = plot.optDouble("x", 0.0).toFloat()
                    view.plotY = plot.optDouble("y", 0.0).toFloat()
                    view.plotW = plot.optDouble("w", 1.0).toFloat().coerceAtLeast(1f)
                    view.plotH = plot.optDouble("h", 1.0).toFloat().coerceAtLeast(1f)
                }
                view.priceLo = json.optDouble("price_lo", 0.0)
                view.priceHi = json.optDouble("price_hi", 0.0)
                view.bandTop = json.optDouble("band_top", 0.0).toFloat()
                view.bookX = json.optDouble("book_x", 0.0).toFloat()
                view.captionsBottom = json.optDouble("captions_bottom", 0.0).toFloat()
                val lines = json.optJSONArray("lines")
                view.lines = if (lines == null) {
                    emptyList()
                } else {
                    List(lines.length()) { at ->
                        val line = lines.optJSONObject(at) ?: JSONObject()
                        LineHit(
                            // As text: an order id may use the whole unsigned 64-bit range.
                            uid = line.optString("uid"),
                            role = line.optString("role"),
                            price = line.optDouble("price"),
                            y = line.optDouble("y").toFloat(),
                            short = line.optBoolean("short"),
                        )
                    }
                }
                view.scaleNow = json.optDouble("scale_pct", 0.0).let { if (it.isNaN()) 0.0 else it }
                view.kind = json.optString("kind", "")
                val vola = json.optJSONArray("vola")
                val ranges = List(3) { at ->
                    if (vola == null || vola.isNull(at)) null else vola.optDouble(at).takeIf { it.isFinite() }
                }
                if (ranges != view.vola) view.vola = ranges
                val leverage = json.optJSONObject("leverage")
                view.leverage = leverage?.optInt("current", 0)?.takeIf { it > 0 }
                view.maxLeverage = leverage?.optInt("max", 0)?.takeIf { it > 0 }
                view.isolated = if (leverage == null || leverage.isNull("isolated")) {
                    null
                } else {
                    leverage.optBoolean("isolated")
                }
                frame.publish()
                view.bitmap = frame.image
                view.frame = view.frame + 1
                view.problem = null
            } else {
                view.problem = Session.describeError(reply)
            }
            val beat = if (view.endMs == null) FRAME_LIVE_MS else FRAME_IDLE_MS
            val wait = (beat - (nowMs() - frameStart)).coerceAtLeast(4L)
            withTimeoutOrNull(wait) { view.redraw.receive() }
        }
    }

    // A double tap is two taps within DOUBLE_TAP_MS near each other: kept here, so a single tap
    // acts at once instead of waiting to learn whether a second follows.
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(view, settings) {
                val slop = viewConfiguration.touchSlop
                val minSpan = 40.dp.toPx()
                val nearTap = 48.dp.toPx()
                val reach = 14.dp.toPx()
                awaitEachGesture {
                    try {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val mouse = down.type == PointerType.Mouse
                    val secondary = currentEvent.buttons.isSecondaryPressed
                    // Held over the plot, the picture stops centring itself.
                    if (!view.inBook(down.position.x)) view.hold()

                    // A press on an own line over the book may drag that line: with a mouse once
                    // it moves, with a finger after holding still a moment. Wherever the pointer
                    // goes then, the line follows, and it is set where the pointer lets go.
                    val hit = if (view.inBook(down.position.x)) view.lineAt(down.position.y, reach) else null
                    // Join and Split act on a take from the moment it is touched until it is let go.
                    view.selected = hit
                    var released = false
                    var holding = false
                    if (hit != null && !mouse) {
                        // 0 still holding, 1 let go (a tap), 2 moved or a second finger came.
                        val outcome = withTimeoutOrNull(LINE_HOLD_MS) {
                            var result = 0
                            while (result == 0) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                result = when {
                                    change == null || !change.pressed -> 1
                                    event.changes.count { it.pressed } > 1 -> 2
                                    (change.position - down.position).getDistance() > slop -> 2
                                    else -> 0
                                }
                            }
                            result
                        }
                        when (outcome) {
                            null -> holding = true
                            1 -> released = true
                            else -> {}
                        }
                    }
                    if (hit != null && !released && (holding || mouse)) {
                        if (holding) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            view.holdForLine()
                            view.drag = Dragged(hit, down.position.y)
                        }
                        var started = holding
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            last = change.position
                            if (!started && (last - down.position).getDistance() > slop) {
                                started = true
                                view.holdForLine()
                            }
                            if (started) {
                                view.drag = Dragged(hit, last.y)
                                change.consume()
                            }
                        }
                        if (started) {
                            view.drag = null
                            val price = view.priceHeld(last.y)
                            // A line held and let go where it was taken stays: only a finger that
                            // went past the slop moves the order.
                            val carried = (last - down.position).getDistance() > slop
                            if (carried && price != null && abs(price - hit.price) > hit.price * 1e-7) {
                                view.pendingMove = PendingMove(hit.uid, hit.role, price, nowMs() + PENDING_MOVE_MS)
                                onMoveLine(hit, price)
                            }
                            return@awaitEachGesture
                        }
                        // A click on a line that did not move: an ordinary click, below.
                        released = true
                    }

                    var moved = false
                    var multi = false
                    // A stretch that began while the chart followed the present keeps following
                    // it, in time and in price: only a drag into the past brings LIVE up.
                    var following = false
                    // Following the present, a finger takes the chart into the past only once it
                    // has gone sideways past the slop: an up-and-down drag stays live.
                    var panning = false
                    var prevCentroid: Offset? = null
                    var prevSpanX = 0f
                    var prevSpanY = 0f
                    var scaleChanged = false
                    // A two-finger tap, as on a touchpad: both land and lift without moving.
                    var multiAt = 0L
                    var multiEnd = 0L
                    var multiFrom = Offset.Zero
                    var multiSpans = Offset.Zero
                    var multiDrift = 0f
                    while (!released) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val spanX = abs(a.x - b.x)
                            val spanY = abs(a.y - b.y)
                            multiEnd = event.changes.maxOf { it.uptimeMillis }
                            if (!multi) {
                                multiAt = multiEnd
                                multiFrom = centroid
                                multiSpans = Offset(spanX, spanY)
                            }
                            multiDrift = maxOf(
                                multiDrift,
                                (centroid - multiFrom).getDistance(),
                                abs(spanX - multiSpans.x),
                                abs(spanY - multiSpans.y),
                            )
                            if (!multi) {
                                multi = true
                                following = view.endMs == null
                                if (following) view.center = null else view.hold()
                            }
                            val before = prevCentroid
                            if (before != null) {
                                // Both at once, as on iOS: the fingers' spread across stretches the
                                // trades in time, their spread up and down the prices; each axis
                                // once the fingers are apart enough along it to measure.
                                if (prevSpanX > minSpan && spanX > minSpan) {
                                    view.zoom(spanX / prevSpanX, centroid.x)
                                }
                                if (prevSpanY > minSpan && spanY > minSpan) {
                                    // An automatic scale becomes a fixed one at the range it shows.
                                    if (view.scalePct <= 0.0 && view.scaleNow > 0.0) view.scalePct = view.scaleNow
                                    if (view.scalePct > 0.0) {
                                        view.scalePct = (view.scalePct * prevSpanY / spanY).coerceIn(0.05, 2000.0)
                                        scaleChanged = true
                                        view.redraw.trySend(Unit)
                                    }
                                }
                                if (!following) view.pan(centroid.x - before.x)
                            }
                            prevCentroid = centroid
                            prevSpanX = spanX
                            prevSpanY = spanY
                            event.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val change = pressed[0]
                            if (!moved && (change.position - down.position).getDistance() > slop) {
                                moved = true
                            }
                            if (moved) {
                                val delta = change.position - change.previousPosition
                                when {
                                    settings.cursorMode && !mouse -> {
                                        val at = (view.cursor ?: change.position) + delta
                                        view.cursor = Offset(
                                            at.x.coerceIn(0f, size.width - 1f),
                                            at.y.coerceIn(0f, size.height - 1f),
                                        )
                                        view.redraw.trySend(Unit)
                                    }
                                    mouse && secondary -> {
                                        // The right button dragged up stretches the prices,
                                        // down squeezes them.
                                        view.hold()
                                        if (view.scalePct <= 0.0 && view.scaleNow > 0.0) {
                                            view.scalePct = view.scaleNow
                                        }
                                        if (view.scalePct > 0.0) {
                                            val factor = 2.0.pow((delta.y / SCALE_DRAG_PX).toDouble())
                                            view.scalePct = (view.scalePct * factor).coerceIn(0.05, 2000.0)
                                            scaleChanged = true
                                            view.redraw.trySend(Unit)
                                        }
                                    }
                                    else -> {
                                        // The picture follows the finger both ways: through
                                        // time and up and down the prices.
                                        view.hold()
                                        if (!panning) {
                                            panning = view.endMs != null ||
                                                abs(change.position.x - down.position.x) > slop
                                        }
                                        if (panning) view.pan(delta.x)
                                        view.nudge(delta.y)
                                    }
                                }
                                change.consume()
                            }
                        } else {
                            // One finger left of two: the stretch goes on with the next pair.
                            prevCentroid = null
                        }
                    }
                    if (scaleChanged) {
                        // The stretched price range is remembered as the chosen scale.
                        val kept = ((view.scalePct * 100.0).roundToLong() / 100.0).coerceAtLeast(0.05)
                        view.scalePct = kept
                        onScaleChange(kept)
                    }
                    val twoFingerTap = multi && !moved && multiDrift < slop && multiEnd - multiAt < 400L
                    if (twoFingerTap && settings.cursorMode && !mouse) {
                        // The touchpad's right click: Short at the cursor.
                        val at = view.cursor
                        val atPrice = at?.let { view.priceAt(it.y) }
                        if (at != null && atPrice != null && view.inBook(at.x)) {
                            if (view.futures) onBookTap(true, atPrice) else onHint("На споте нет Short")
                        }
                        return@awaitEachGesture
                    }
                    if (moved || multi) return@awaitEachGesture

                    // A tap or a click.
                    val pos = down.position
                    val now = down.uptimeMillis
                    val double = now - lastTapAt < DOUBLE_TAP_MS &&
                        (pos - lastTapPos).getDistance() < nearTap
                    lastTapAt = if (double) 0L else now
                    lastTapPos = pos
                    val price = view.priceAt(pos.y)
                    val onBook = view.inBook(pos.x) && price != null
                    if (settings.cursorMode) {
                        if (mouse) {
                            view.cursor = pos
                            view.redraw.trySend(Unit)
                            // On the book the left button buys (Long), the right one shorts.
                            if (onBook && price != null && (settings.spotSingleTap || double)) {
                                when {
                                    !secondary -> onBookTap(false, price)
                                    view.futures -> onBookTap(true, price)
                                    else -> onHint("На споте нет Short")
                                }
                            } else if (!onBook && double && !secondary) {
                                view.followPresent()
                            }
                        } else if (double) {
                            // The touchpad's double tap: Long (Buy on spot) at the cursor when it
                            // stands on the book; elsewhere back to the present.
                            val at = view.cursor
                            val atPrice = at?.let { view.priceAt(it.y) }
                            if (at != null && atPrice != null && view.inBook(at.x)) {
                                onBookTap(false, atPrice)
                            } else {
                                view.followPresent()
                            }
                        }
                        return@awaitEachGesture
                    }
                    if (!onBook || price == null) {
                        if (double) view.followPresent()
                        return@awaitEachGesture
                    }
                    when {
                        holdTake.value -> {
                            holdTake.used = true
                            onTake(price, takeStep)
                            takeStep += 1
                        }
                        !view.futures -> if (settings.spotSingleTap || double) onBookTap(false, price)
                        holdShort.value -> {
                            holdShort.used = true
                            onBookTap(true, price)
                        }
                        holdLong.value -> {
                            holdLong.used = true
                            onBookTap(false, price)
                        }
                        else -> onHint("Удерживайте Short или Long и нажмите на стакан")
                    }
                    } finally {
                        view.selected = null
                        if (view.touching) view.letGo()
                    }
                }
            }
            .pointerInput(view, settings.cursorMode) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val turn = change.scrollDelta.y
                                if (turn != 0f) {
                                    // Wheel toward the hand zooms in, away from it out.
                                    view.zoom(if (turn < 0f) 0.8f else 1.25f, change.position.x)
                                    change.consume()
                                }
                            }
                            PointerEventType.Move -> {
                                // A hovering mouse carries the crosshair with it.
                                val hovering = change.type == PointerType.Mouse && !change.pressed
                                if (settings.cursorMode && hovering) {
                                    view.cursor = change.position
                                    view.redraw.trySend(Unit)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frame = view.frame
            val bitmap = view.bitmap
            if (bitmap != null && frame > 0) {
                drawImage(bitmap)
            }
        }
        ChartNote(view)
        val problem = view.problem
        if (problem != null) {
            Text(
                problem,
                color = Palette.red,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        }
    }
}

/** The chart's small note: one line at the top middle, gone after a moment. */
@Composable
private fun BoxScope.ChartNote(view: ChartView) {
    val note = view.note ?: return
    LaunchedEffect(note) {
        delay(if (note.error) NOTE_MS * 2 else NOTE_MS)
        if (view.note == note) view.note = null
    }
    Text(
        note.text,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        color = if (note.error) Color(0xFFFF8A80) else Color(0xFFE0E0E0),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 30.dp)
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xD0101010))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The buttons on the picture. Short and Long sit at the plot's left edge beside the price
 * scale: Short under the funding and detect captions, Long at the very bottom over the volume
 * scale. Cancel Buy and Panic Sell sit at the bottom right, just above the volume band; while
 * the window is dragged into the past, LIVE brings it back.
 *
 * Short and Long belong to touch mode, where they are held while the other hand taps the book.
 * A spot market has neither: a tap on the book places its buy.
 */
@Composable
private fun ChartButtons(
    view: ChartView,
    settings: ChartSettings,
    joinSplit: Boolean,
    onJoin: () -> Unit,
    onSplit: () -> Unit,
    onCancelBuy: () -> Unit,
    onPanicSell: () -> Unit,
) {
    val bitmap = view.bitmap
    if (bitmap == null || view.bookX <= 0f || view.bandTop <= 0f) return
    val density = LocalDensity.current.density
    val end = ((bitmap.width - view.bookX) / density).dp + 6.dp
    val bottom = ((bitmap.height - view.bandTop) / density).dp + 6.dp
    val left = (view.plotX / density).dp + 6.dp
    val floor = ((bitmap.height - view.plotY - view.plotH) / density).dp + 4.dp
    val sides = view.futures && !settings.cursorMode
    val takeShown = !settings.cursorMode
    SideEffect {
        if (!sides && view.leftInset != 0f) {
            view.leftInset = 0f
            view.redraw.trySend(Unit)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (sides) {
            SideButton(
                label = "Short",
                color = terminalRed,
                hold = holdShort,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = left, top = (view.captionsBottom / density).dp + 4.dp),
            )
            SideButton(
                label = "Long",
                color = terminalGreen,
                hold = holdLong,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = left, bottom = floor)
                    .onSizeChanged {
                        // The picture moves its volume scale out from under the button.
                        val inset = it.width + 8f * density
                        if (abs(view.leftInset - inset) > 1f) {
                            view.leftInset = inset
                            view.redraw.trySend(Unit)
                        }
                    },
            )
        }
        if (takeShown && joinSplit) {
            // Split between Short and Take, Join between Take and Long: pressed while a take is
            // held on the book (or just after it is let go).
            val split = Color(0xFF7FC9FF)
            TapButton(
                label = "Split",
                color = split,
                onClick = onSplit,
                modifier = Modifier
                    .align(BiasAlignment(-1f, -0.45f))
                    .padding(start = left),
            )
            TapButton(
                label = "Join",
                color = split,
                onClick = onJoin,
                modifier = Modifier
                    .align(BiasAlignment(-1f, 0.45f))
                    .padding(start = left),
            )
        }
        if (takeShown) {
            // Between Short and Long: held while a tap on the book moves the exit or the entries.
            SideButton(
                label = "Take",
                color = Color(0xFFB8BCC4),
                hold = holdTake,
                hint = "Держите Take и нажмите на стакан — переставит выход или ордера",
                onPress = { takeStep = 0 },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = left),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = end, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (view.endMs != null) {
                PlotButton(
                    "▶ LIVE",
                    text = terminalGreen,
                    fill = terminalGreen.copy(alpha = 0.16f),
                    edge = terminalGreen,
                    onClick = { view.followPresent() },
                )
            }
            PlotButton(
                "Cancel Buy",
                text = chartLabel,
                fill = chartLabel.copy(alpha = 0.16f),
                edge = chartLabel.copy(alpha = 0.7f),
                onClick = onCancelBuy,
            )
            PlotButton(
                "Panic Sell",
                text = terminalRed,
                fill = terminalRed.copy(alpha = 0.16f),
                edge = terminalRed,
                onClick = onPanicSell,
            )
        }
    }
}

/** Join or Split: a plain tap, in the side buttons' shape. */
@Composable
private fun TapButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .width(58.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.5.dp, color, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * Short, Long or Take: pressed and held while another finger taps the book, lit while held. A
 * press that placed nothing says how it works, in a small note beside it.
 */
@Composable
private fun SideButton(
    label: String,
    color: Color,
    hold: Hold,
    modifier: Modifier = Modifier,
    hint: String = "Держите $label и нажмите на стакан",
    onPress: () -> Unit = {},
) {
    var held by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .clip(shape)
                .background(if (held) color.copy(alpha = 0.55f) else color.copy(alpha = 0.16f))
                .border(1.5.dp, color, shape)
                .pointerInput(hold) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        onPress()
                        hold.value = true
                        hold.used = false
                        held = true
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        hold.value = false
                        held = false
                        if (!hold.used) showHint = true
                    }
                }
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (held) Color.White else color,
                maxLines = 1,
            )
        }
        if (showHint) {
            LaunchedEffect(Unit) {
                delay(2500L)
                showHint = false
            }
            Text(
                hint,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = Color(0xFFE0E0E0),
                maxLines = 2,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xD0101010))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PlotButton(label: String, text: Color, fill: Color, edge: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = text, maxLines = 1)
    }
}

/**
 * The detect feed beside the chart: the chart's market, exchange and core on top, then a card
 * per detect that is still alive, newest first. A card opens its market's chart.
 */
@Composable
private fun DetectPanel(
    market: String,
    exchange: String,
    coreName: String,
    vola: List<Double?>,
    cards: List<Card>,
    onPick: (Long, String) -> Unit,
    modifier: Modifier,
) {
    // A clock of its own: the countdown moves every second without a new snapshot.
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500L)
            now = nowMs()
        }
    }
    val alive = cards.filter { now < it.bornMs + it.ttlMs }
    // Settings → График: full cards or only their first two lines.
    val layers by ChartPrefs.layers(platformContext()).collectAsState()
    Column(modifier = modifier.background(panelBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(Palette.chartPanelHead)
                .padding(start = 8.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    market,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (market.length > 10) 11.sp else 13.sp,
                    color = Palette.chartStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (exchange.isNotEmpty()) {
                    Text(
                        exchange,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = chartLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    coreName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = if (Palette.light) Color(0xFF9A5B00) else Color(0xFFFFB347),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The price's range over 3 h, 1 h and 15 min, as the Screener measures it: spread over
            // the header's height, one beside each of its lines.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 3.dp, top = 2.dp, bottom = 1.dp),
            ) {
                for ((caption, value) in listOf("3h", "1h", "15m").zip(vola)) {
                    Text(
                        "$caption:${volaText(value)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            value == null -> Palette.textDim
                            value >= 10.0 -> Palette.terminalRed
                            value >= 3.0 -> if (Palette.light) Color(0xFF9A5B00) else Color(0xFFFFB347)
                            else -> chartLabel
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (alive.isEmpty()) {
                Text(
                    "Детектов пока нет — карточка появится, когда сработает ядро",
                    color = chartLabel,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(10.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(alive.distinctBy { "${it.core}:${it.market}" }, key = { "${it.core}:${it.market}" }) { card ->
                        DetectCard(
                            card = card,
                            secondsLeft = ((card.bornMs + card.ttlMs - now + 999L) / 1000L)
                                .coerceAtLeast(0L),
                            compact = layers.compactDetects,
                            onClick = { onPick(card.core, card.market) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One card, narrow and dense: a rail in the core's colour; the coin with how the price moved
 * since the detect; the kind badge and the core with the countdown under the move; then, unless
 * [compact], the day's line with the hour's and the day's change beside it, the strategy that
 * fired and the detect's own line. A long press shows the whole line.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DetectCard(card: Card, secondsLeft: Long, compact: Boolean, onClick: () -> Unit) {
    val overrides by AppPrefs.overrides.collectAsState()
    val coreColor = parseColor(overrides[card.core]?.color ?: card.coreColor, Color(0xFF7FC9FF))
    val badgeColor = parseColor(card.badgeColor, chartLabel)
    var showMsg by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(5.dp))
            .background(cardBackground)
            .combinedClickable(onClick = onClick, onLongClick = { showMsg = true }),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(coreColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 5.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The coin, whole, and the move since the detect.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.coin,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = Palette.chartStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val since = card.sincePct
                Text(
                    if (since == null) "…" else fmt("%+.2f%%", since),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = when {
                        since == null || since == 0.0 -> Palette.textDim
                        since < 0.0 -> terminalRed
                        else -> terminalGreen
                    },
                    maxLines = 1,
                )
            }
            // The kind and the core, and the countdown under the move.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Chip(card.badge, badgeColor)
                    Chip(card.coreName, coreColor)
                }
                Text(
                    "${secondsLeft}s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    color = Palette.textDim,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
            if (!compact) {
                // The day's line, with the hour's and the day's change, labelled.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (card.line.size >= 2) {
                            Sparkline(
                                line = card.line,
                                falling = (card.delta24h ?: 0.0) < 0.0,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("график…", fontSize = 8.sp, lineHeight = 10.sp, color = Palette.textDim)
                        }
                    }
                    Column(
                        modifier = Modifier.padding(start = 4.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        DeltaText("1ч", card.delta1h)
                        DeltaText("24ч", card.delta24h)
                    }
                }
                val why = listOf(card.strategy, card.msg).filter { it.isNotBlank() }.distinct()
                if (why.isNotEmpty()) {
                    Text(
                        why.joinToString(" · "),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = Palette.textDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (showMsg) {
        MoonDialog(
            onDismissRequest = { showMsg = false },
            title = { Text("${card.coin} · ${card.badge}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (card.strategy.isNotBlank()) Text("Стратегия: ${card.strategy}")
                    Text(card.msg.ifBlank { "Ядро не прислало текст детекта." })
                    Text(
                        "Ядро: ${card.coreName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textDim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMsg = false
                    onClick()
                }) { Text("Открыть график") }
            },
            dismissButton = { TextButton(onClick = { showMsg = false }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun Chip(label: String, color: Color) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            .padding(horizontal = 3.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeltaText(label: String, percent: Double?) {
    Text(
        "$label " + if (percent == null) "…" else fmt("%+.1f%%", percent),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 10.sp,
        color = when {
            percent == null -> Palette.textDim
            percent < 0.0 -> terminalRed
            else -> terminalGreen
        },
        maxLines = 1,
    )
}

/** The day's closes as one line, red when the day is down. */
@Composable
private fun Sparkline(line: List<Float>, falling: Boolean, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (line.size < 2) return@Canvas
        val low = line.min()
        val high = line.max()
        val range = (high - low).takeIf { it > 0f } ?: 1f
        val path = Path()
        line.forEachIndexed { index, value ->
            val x = size.width * index / (line.size - 1)
            val y = size.height * (1f - (value - low) / range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = if (falling) terminalRed else terminalGreen,
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }
}

/** `#rrggbb` as a colour; one too dark to read on a card, or unreadable, becomes [fallback]. */
private fun parseColor(hex: String, fallback: Color): Color {
    val value = hex.removePrefix("#").toLongOrNull(16) ?: return fallback
    if (hex.length != 7) return fallback
    val red = ((value shr 16) and 0xFF).toInt()
    val green = ((value shr 8) and 0xFF).toInt()
    val blue = (value and 0xFF).toInt()
    val light = (red * 299 + green * 587 + blue * 114) / 1000
    return if (light < 70) fallback else Color(red, green, blue)
}

/** One market the picker lists: found by the search or ranked. */
private data class PickRow(
    val core: Long,
    val market: String,
    val rangePct: Double?,
    val changePct: Double?,
    val volume: Double?,
    /** Traded over the last hour in dollars (another quote at its USDT price); null while unknown. */
    val hourUsdt: Double? = null,
)

private fun parsePickRows(reply: app.moonmobile.core.Reply): List<PickRow>? {
    if (!reply.ok) return null
    val rows = reply.json.optJSONArray("rows") ?: return emptyList()
    return List(rows.length()) { at ->
        val o = rows.optJSONObject(at) ?: JSONObject()
        fun number(key: String): Double? =
            if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
        PickRow(
            core = o.optLong("core"),
            market = o.optString("market"),
            rangePct = number("range_pct"),
            changePct = number("change_pct"),
            volume = number("vol") ?: number("vol_24h"),
            hourUsdt = number("vol_1h_usdt"),
        )
    }
}

/** «Ликвидность» leaves out a market that traded less than this over the last hour, in dollars. */
private const val LIQUID_HOUR_USDT = 5_000.0

/** The ranking's intervals: the key the bridge takes and the button's caption. */
private val rankIntervals = listOf("1m" to "1м", "5m" to "5м", "15m" to "15м", "1h" to "1ч", "1d" to "1д")

/** The search box of the top bar while the picker is open: compact, to leave the room below. */
@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = chartLabel),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        modifier = modifier
            .clip(shape)
            .background(Palette.chartButton)
            .border(1.dp, Palette.chartEdge, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { field ->
            Box {
                if (query.isEmpty()) {
                    Text("Поиск рынка: BTC, ETHUSDT…", style = MaterialTheme.typography.bodyMedium, color = Palette.textDim)
                }
                field()
            }
        },
    )
}

/**
 * What the chart remembers for the session: the open tabs, oldest first, and the scale each tab
 * shows while the chart is open (a closed tab, or the chart left, forgets it).
 */
internal object ChartSession {
    val tabs = mutableStateListOf<Pair<Long, String>>()
    val scales = HashMap<String, Double>()

    fun open(core: Long, market: String) {
        if (tabs.none { it.first == core && it.second == market }) tabs.add(core to market)
    }
}

/** `S` for a spot core, `F` for futures, nothing while not known. */
private fun kindLetter(core: Core?): String = when (core?.kind) {
    "spot" -> "S"
    "futures", "quarterly" -> "F"
    else -> ""
}

/**
 * The chart tabs across the top: П opens the positions, then every chart opened this session with
 * its exchange's logo, the market and S or F, closed with its ×. One tap switches.
 */
@Composable
private fun TabStrip(
    cores: List<Core>,
    current: Pair<Long, String>?,
    leading: Dp,
    onSelect: (Pair<Long, String>) -> Unit,
    onClose: (Pair<Long, String>) -> Unit,
    balance: Pair<String, String>?,
    lastDeal: LastDeal?,
    onDeal: (LastDeal) -> Unit,
) {
    val byId = remember(cores) { cores.associateBy { it.id } }
    // On a tablet the chart tabs are taller, for the finger.
    val tablet = isTablet()
    val tabHeight = chartTabHeight(tablet)
    val tabFont = if (tablet) 12.sp else 10.sp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(tabHeight + 2.dp)
            .padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val shape = RoundedCornerShape(6.dp)
        // П lies over this place (PositionsButton), larger than the row.
        Spacer(Modifier.width(leading))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in ChartSession.tabs.toList()) {
                val core = byId[tab.first]
                val selected = tab == current
                Row(
                    modifier = Modifier
                        .height(tabHeight)
                        .clip(shape)
                        .background(if (selected) Palette.accent.copy(alpha = 0.18f) else Palette.chartButton)
                        .border(1.dp, if (selected) Palette.accent else Palette.chartEdge, shape)
                        .clickable { onSelect(tab) }
                        .padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val logo = core?.let { brandLogo(it.brand) }
                    if (logo != null) {
                        Image(
                            painter = imagePainter(logo),
                            contentDescription = core?.exchange,
                            modifier = Modifier
                                .size(if (tablet) 14.dp else 10.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        tab.second,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = tabFont,
                        lineHeight = tabFont * 1.1f,
                        color = if (selected) Palette.chartStrong else chartLabel,
                        maxLines = 1,
                    )
                    val letter = kindLetter(core)
                    if (letter.isNotEmpty()) {
                        Text(
                            " $letter",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (tablet) 11.sp else 9.sp,
                            lineHeight = if (tablet) 12.sp else 10.sp,
                            color = if (letter == "S") Palette.amber else Color(0xFF7FC9FF),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable { onClose(tab) }
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "×",
                            fontSize = if (tablet) 15.sp else 12.sp,
                            lineHeight = if (tablet) 15.sp else 12.sp,
                            color = Palette.textDim,
                        )
                    }
                }
            }
        }
        // Over the detect cards, right-aligned: the newest closed trade, then what the chart's
        // core really has free. The two sit together at the right, so the tabs keep the rest of
        // the row; the box grows to the left only when they need more than the cards' width.
        Row(
            modifier = Modifier.widthIn(min = DETECT_PANEL_WIDTH).padding(start = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `#SHAZ +4.00 +1.25%`: the coin, the profit in the market's own quote currency with
            // no name to it, and the percent. A tap opens its chart.
            if (lastDeal != null) {
                val money = lastDeal.profitNative ?: lastDeal.profitUsdt
                val good = (money ?: lastDeal.profitPct ?: 0.0) >= 0.0
                val amount = when {
                    money != null -> dealMoney(money).removeSuffix("$")
                    lastDeal.profitPct != null -> fmt("%+.2f%%", lastDeal.profitPct)
                    else -> ""
                }
                // The percent beside the money, when the money is what the amount shows.
                val percent = lastDeal.profitPct
                    ?.takeIf { money != null }
                    ?.let { fmt("%+.2f%%", it) }
                Row(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .widthIn(max = 240.dp)
                        .height(20.dp)
                        .clip(shape)
                        .background((if (good) terminalGreen else terminalRed).copy(alpha = 0.12f))
                        .clickable(enabled = lastDeal.market.isNotEmpty()) { onDeal(lastDeal) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val tint = if (good) terminalGreen else terminalRed
                    for ((part, color) in listOf(
                        "#${bareCoin(lastDeal.coin)} " to Palette.chartStrong,
                        amount to tint,
                        (percent?.let { " $it" } ?: "") to tint,
                    )) {
                        if (part.isEmpty()) continue
                        Text(
                            part,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            if (balance != null) {
                Text(
                    balance.first,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 14.sp,
                    color = Palette.chartStrong,
                    maxLines = 1,
                    softWrap = false,
                )
                if (balance.second.isNotEmpty()) {
                    Text(
                        " ${balance.second}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        color = chartLabel,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Every open position on every core, the strategies' included, over the chart: the exchange's
 * logo, the market, its size and the result in percent and in money, as the snapshots come. A tap
 * opens the market's chart.
 */
@Composable
private fun PositionsPanel(
    snapshot: Snapshot?,
    cores: List<Core>,
    current: Pair<Long, String>?,
    onPick: (Long, String) -> Unit,
    onCancel: (Order) -> Unit,
    onControl: (Core, Boolean) -> Unit,
    onRun: (Core, Boolean) -> Unit,
    onReport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = platformContext()
    val byId = remember(cores) { cores.associateBy { it.id } }
    // Recomposes when the remembered own orders change.
    val mineKeys by Session.mine.collectAsState()
    val isMine: (Order) -> Boolean = { it.mine || "${it.core}:${it.uid}" in mineKeys }
    // "Order app": only the orders and positions placed from this phone; off lists every one,
    // the strategies' too.
    var appOnly by remember { mutableStateOf(AppPrefs.flag(context, "positions_app_only", true)) }
    val placed = snapshot?.orders.orEmpty()
        .filter { !it.filled && byId.containsKey(it.core) && (!appOnly || isMine(it)) }
        .sortedWith(compareBy({ byId[it.core]?.name.orEmpty() }, { it.coin }))
    // A cross tapped once, and the orders whose cancel went out (until they leave the list).
    var armed by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var sent by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(armed) {
        if (armed.isNotEmpty()) {
            delay(CANCEL_ARM_MS)
            val now = nowMs()
            armed = armed.filterValues { now - it < CANCEL_ARM_MS }
        }
    }
    val live = placed.map { "${it.core}:${it.uid}" }.toSet()
    if (sent.any { it !in live }) sent = sent.filter { it in live }.toSet()
    val positions = snapshot?.orders.orEmpty()
        .filter { it.filled && !it.pending && byId.containsKey(it.core) && (!appOnly || isMine(it)) }
        .sortedWith(compareBy({ byId[it.core]?.name.orEmpty() }, { it.coin }))
    val shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    // The open chart's core, whose watcher the Control button shows and switches.
    val chartCore = current?.first?.let { byId[it] }
    val control = chartCore?.control.orEmpty()
    Column(
        modifier = modifier
            .clip(shape)
            .background(Palette.chartPanelHead.copy(alpha = 0.97f))
            .border(1.dp, Palette.chartEdge, shape),
    ) {
        // The buttons at the very top, the cross at the end of their row, where it always was.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        appOnly = !appOnly
                        AppPrefs.setFlag(context, "positions_app_only", appOnly)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = appOnly,
                    onCheckedChange = null,
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                )
                Spacer(Modifier.width(6.dp))
                Text("Order app", fontSize = 12.sp, color = Palette.chartStrong)
            }
            Spacer(Modifier.weight(1f))
            // The report: every closed trade, as in MoonTerminal.
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.accent.copy(alpha = 0.18f))
                    .border(1.dp, Palette.accent, RoundedCornerShape(6.dp))
                    .clickable(onClick = onReport)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Отчёт", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
            }
            // Stop: the open chart core's ticked strategies stop; Start runs them again.
            val trading = chartCore?.run?.trading
            val runUsable = chartCore != null && chartCore.run.online && trading != null
            val runTint = when (trading) {
                true -> terminalRed
                false -> terminalGreen
                null -> Palette.textDim
            }
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(runTint.copy(alpha = if (runUsable) 0.22f else 0.08f))
                    .border(1.dp, runTint.copy(alpha = if (runUsable) 1f else 0.4f), RoundedCornerShape(6.dp))
                    .clickable(enabled = runUsable) { chartCore?.let { onRun(it, trading != true) } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (trading == false) "Start" else "Stop",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (runUsable) runTint else Palette.textDim.copy(alpha = 0.6f),
                )
            }
            // Green: a watcher follows this core's orders; red: its order control is off here;
            // no watcher address on the core (or not known yet): not pressable.
            val usable = chartCore != null && (control == "on" || control == "off")
            val tint = when (control) {
                "on" -> terminalGreen
                "off" -> terminalRed
                else -> Palette.textDim
            }
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = if (usable) 0.22f else 0.08f))
                    .border(1.dp, tint.copy(alpha = if (usable) 1f else 0.4f), RoundedCornerShape(6.dp))
                    .clickable(enabled = usable) { chartCore?.let { onControl(it, control != "on") } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Control",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (usable) tint else Palette.textDim.copy(alpha = 0.6f),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = chartLabel, fontSize = 16.sp)
            }
        }
        if (positions.isEmpty() && placed.isEmpty()) {
            Text(
                "Открытых позиций нет",
                color = Palette.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(positions.distinctBy { "${it.core}:${it.uid}" }, key = { "${it.core}:${it.uid}" }) { order ->
                val core = byId[order.core]
                val selected = current == (order.core to order.market)
                val pct = order.pnlPct
                val money = positionMoney(order)
                val color = when {
                    pct == null -> chartLabel
                    pct < 0.0 -> terminalRed
                    else -> terminalGreen
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Palette.accent.copy(alpha = 0.16f) else cardBackground)
                        .clickable { onPick(order.core, order.market) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val logo = core?.let { brandLogo(it.brand) }
                    if (logo != null) {
                        Image(
                            painter = imagePainter(logo),
                            contentDescription = core?.exchange,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(order.coin.ifEmpty { order.market })
                                append(if (order.short) " Short" else " Long")
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Palette.chartStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(core?.name.orEmpty(), order.strategy.ifEmpty { "ручной" })
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = Palette.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            compactMoney(order.size * order.buyPrice) + " $",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = chartLabel,
                        )
                        Text(
                            buildString {
                                append(if (pct == null) "—" else fmt("%+.2f%%", pct))
                                if (money != null) append(" ").append(signedMoney(money))
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = color,
                        )
                    }
                }
            }
            // The orders placed and not filled yet, each with its price and a cross that
            // cancels it: the first tap arms it, the second within a few seconds cancels.
            item(key = "orders-head") {
                Text(
                    if (appOnly) "Ордера с телефона: ${placed.size}" else "Ордера: ${placed.size}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Palette.chartStrong,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            if (placed.isEmpty()) {
                item(key = "orders-none") {
                    Text(
                        "Выставленных ордеров нет",
                        color = Palette.textDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            items(placed.distinctBy { "${it.core}:${it.uid}" }, key = { "o:${it.core}:${it.uid}" }) { order ->
                val core = byId[order.core]
                val selected = current == (order.core to order.market)
                val key = "${order.core}:${order.uid}"
                val armedAt = armed[key]
                val isArmed = armedAt != null && nowMs() - armedAt < CANCEL_ARM_MS
                val isSent = key in sent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Palette.accent.copy(alpha = 0.16f) else cardBackground)
                        .clickable { onPick(order.core, order.market) }
                        .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val logo = core?.let { brandLogo(it.brand) }
                    if (logo != null) {
                        Image(
                            painter = imagePainter(logo),
                            contentDescription = core?.exchange,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(order.coin.ifEmpty { order.market })
                                append(if (order.short) " Short" else " Long")
                                if (order.pending) append(" · ждёт")
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (order.short) terminalRed else terminalGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(core?.name.orEmpty(), order.strategy.ifEmpty { "ручной" })
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = Palette.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "по ${formatPrice(order.buyPrice)}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Palette.chartStrong,
                        )
                        Text(
                            compactMoney(order.size * order.buyPrice) + " $",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = chartLabel,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .height(34.dp)
                            .widthIn(min = 34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isArmed) Palette.red else Color.Transparent)
                            .clickable(enabled = !isSent) {
                                if (isArmed) {
                                    armed = armed - key
                                    sent = sent + key
                                    onCancel(order)
                                } else {
                                    armed = armed + (key to nowMs())
                                }
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when {
                                isSent -> "…"
                                isArmed -> "Отменить"
                                else -> "✕"
                            },
                            fontSize = if (isArmed) 11.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isArmed) Color.White else terminalRed,
                        )
                    }
                }
            }
        }
    }
}

/** The width of the detect cards' column beside the chart. */
private val DETECT_PANEL_WIDTH = 136.dp

/** A range in the detect panel's header to a tenth: `12.4`, `4.3`, `0.8`, or a dash while unknown. */
private fun volaText(value: Double?): String = when {
    value == null -> "—"
    value >= 100.0 -> fmt("%.0f", value)
    else -> fmt("%.1f", value)
}

/** An amount with no thousands spaces: 12191.62, or the small change of a coin balance. */
private fun plainAmount(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    if (kotlin.math.abs(value) >= 1.0) return fmt("%.2f", value)
    return fmt("%.6f", value).trimEnd('0').trimEnd('.')
}

/** How long a tapped cross waits for the second tap that cancels the order. */
private const val CANCEL_ARM_MS = 3_000L

/** A position's result in its quote currency. */
internal fun positionMoney(order: Order): Double? {
    val pct = order.pnlPct ?: return null
    val cost = order.size * order.buyPrice
    if (!(cost > 0.0)) return null
    return cost * pct / 100.0
}

/** `+12.34 USDT`, `-0.50 USDC`: money in a market's quote currency, signed. */
internal fun signedQuote(value: Double, quote: String): String {
    val text = if (kotlin.math.abs(value) >= 1000.0) compactMoney(kotlin.math.abs(value)) else fmt("%.2f", kotlin.math.abs(value))
    return (if (value < 0.0) "-" else "+") + text + if (quote.isEmpty()) "" else " $quote"
}

/** `+12.34$`, `-0.50$`. */
private fun signedMoney(value: Double): String {
    val text = if (kotlin.math.abs(value) >= 1000.0) compactMoney(kotlin.math.abs(value)) else fmt("%.2f", kotlin.math.abs(value))
    return (if (value < 0.0) "-" else "+") + text + "$"
}

/** The logo of an exchange, from the terminal's own set; `null` for one it has none of. */
internal fun brandLogo(brand: String): String? = when (brand) {
    "binance" -> "ex_binance"
    "bybit" -> "ex_bybit"
    "okx" -> "ex_okx"
    "gate" -> "ex_gate"
    "bitget" -> "ex_bitget"
    "htx" -> "ex_htx"
    "hyperliquid" -> "ex_hyperliquid"
    else -> null
}

/**
 * Choosing what to draw, in two halves under the search box of the top bar, each two columns
 * wide. On the left the markets matching what is typed, filtered to Spot and Futures as ticked,
 * or with nothing typed the markets opened before. On the right every traded market ranked by
 * how far its price moved over the chosen interval or by the day's volume. Both search the cores
 * chosen with the "Ядра" button.
 */
@Composable
private fun MarketPicker(
    cores: List<Core>,
    searched: List<Long>,
    query: String,
    onPick: (Long, String) -> Unit,
) {
    val context = platformContext()
    var found by remember { mutableStateOf<List<PickRow>?>(null) }
    var ranked by remember { mutableStateOf<List<PickRow>?>(null) }
    var byVolume by remember { mutableStateOf(AppPrefs.text(context, "rank_by", "volatility") == "volume") }
    var interval by remember { mutableStateOf(AppPrefs.text(context, "rank_interval", "5m")) }
    var spot by remember { mutableStateOf(AppPrefs.flag(context, "pick_spot", true)) }
    var futures by remember { mutableStateOf(AppPrefs.flag(context, "pick_futures", true)) }
    var liquid by remember { mutableStateOf(AppPrefs.flag(context, "pick_liquidity", false)) }
    // The exchanges ticked under «Биржи», by brand; none ticked shows every exchange.
    var brands by remember {
        mutableStateOf(
            AppPrefs.text(context, "pick_exchanges", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
    // Set by a tap on an interval or a sort until the bridge answers for it.
    var refreshing by remember { mutableStateOf(false) }
    // The latest figures of the left column's markets, refreshed while it is shown.
    var figures by remember { mutableStateOf<Map<String, PickRow>>(emptyMap()) }
    val byId = remember(cores) { cores.associateBy { it.id } }
    val showCore = searched.size > 1
    val coreList = remember(searched) { JSONArray().also { list -> searched.forEach { list.put(it) } } }
    // A market passes the filter by what its core trades; a core of unknown kind always does.
    // With Liquidity on, in every column, a market that traded less than LIQUID_HOUR_USDT over the
    // last hour, or did not trade at all over the interval, is left out; one not measured yet stays.
    val passes: (PickRow) -> Boolean = { row ->
        val kind = when (byId[row.core]?.kind) {
            "spot" -> spot
            "futures", "quarterly" -> futures
            else -> true
        }
        val traded = !liquid || (
            (row.hourUsdt ?: LIQUID_HOUR_USDT) >= LIQUID_HOUR_USDT &&
                (row.volume ?: 1.0) > 0.0 && (row.rangePct ?: 1.0) > 0.0
            )
        val exchange = brands.isEmpty() || byId[row.core]?.brand?.let { it in brands } != false
        kind && traded && exchange
    }
    // The exchanges the searched cores trade on, for the «Биржи» list.
    val exchanges = remember(searched, byId) {
        searched.mapNotNull { byId[it] }
            .map { it.brand.ifBlank { it.exchange.lowercase() } to exchangeTitle(it) }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
    val history = remember { AppPrefs.history(context) }
    val pick: (Long, String) -> Unit = { pickedCore, pickedMarket ->
        AppPrefs.addHistory(context, pickedCore, pickedMarket)
        onPick(pickedCore, pickedMarket)
    }

    LaunchedEffect(searched, query) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        // Wait out the typing: one search per pause, not one per letter.
        delay(250L)
        val reply = Session.call(
            JSONObject()
                .put("op", "search")
                .put("cores", coreList)
                .put("query", query.trim())
                .put("limit", 80),
        )
        found = parsePickRows(reply).orEmpty()
    }
    val leftBase = if (query.isBlank()) {
        history.map { (core, market) -> PickRow(core, market, null, null, null) }
            .filter { byId.containsKey(it.core) }
    } else {
        found
    }
    val leftKeys = leftBase.orEmpty().map { it.core to it.market }
    LaunchedEffect(leftKeys, interval) {
        if (leftKeys.isEmpty()) return@LaunchedEffect
        while (true) {
            val items = JSONArray()
            leftKeys.take(80).forEach { items.put(JSONObject().put("core", it.first).put("market", it.second)) }
            val reply = Session.call(
                JSONObject().put("op", "figures").put("items", items).put("interval", interval),
            )
            parsePickRows(reply)?.let { rows ->
                figures = figures + rows.associateBy { "${it.core}:${it.market}" }
            }
            delay(5_000L)
        }
    }

    LaunchedEffect(searched, byVolume, interval) {
        // At once for the interval just chosen, again as the cores' markets tables answer the
        // request this ranking sent, then every few seconds.
        var round = 0
        while (true) {
            val reply = Session.call(
                JSONObject()
                    .put("op", "rank")
                    .put("cores", coreList)
                    .put("by", if (byVolume) "volume" else "volatility")
                    .put("interval", interval)
                    .put("limit", 300),
            )
            parsePickRows(reply)?.let { ranked = it }
            refreshing = false
            delay(
                when (round++) {
                    0 -> 900L
                    1 -> 1_500L
                    else -> 5_000L
                },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // The filters scroll sideways when the half is narrow; the caption stays on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    KindBox("Spot", spot) {
                        spot = it
                        AppPrefs.setFlag(context, "pick_spot", it)
                    }
                    KindBox("Futures", futures) {
                        futures = it
                        AppPrefs.setFlag(context, "pick_futures", it)
                    }
                    Toggle("Ликвидность", liquid) {
                        liquid = !liquid
                        AppPrefs.setFlag(context, "pick_liquidity", liquid)
                    }
                    ExchangeFilter(exchanges, brands) { chosen ->
                        brands = chosen
                        AppPrefs.setText(context, "pick_exchanges", chosen.joinToString(","))
                    }
                }
                Text(
                    if (query.isBlank()) "Недавние" else "Найдено",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.textDim,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                )
            }
            // The interval chosen on the right measures these too.
            val rows = leftBase?.map { figures["${it.core}:${it.market}"] ?: it }
            when {
                searched.isEmpty() -> PickNote("Выберите ядра кнопкой «Ядра».")
                rows == null -> PickNote("Ищу…")
                rows.filter(passes).isEmpty() -> PickNote(
                    if (query.isBlank()) "Наберите монету — например, BTC." else "Ничего не найдено.",
                )
                else -> PickGrid(rows.filter(passes), byId, showCore, ranking = null, onPick = pick)
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 3.dp).horizontalScroll(rememberScrollState()),
            ) {
                Toggle("Волатильность", !byVolume) {
                    if (byVolume) {
                        byVolume = false
                        refreshing = true
                        // Sorted at once by what is already known; the bridge's answer follows.
                        ranked = ranked?.sortedByDescending { it.rangePct ?: -1.0 }
                        AppPrefs.setText(context, "rank_by", "volatility")
                    }
                }
                Toggle("Объём", byVolume) {
                    if (!byVolume) {
                        byVolume = true
                        refreshing = true
                        ranked = ranked?.sortedByDescending { it.volume ?: -1.0 }
                        AppPrefs.setText(context, "rank_by", "volume")
                    }
                }
                Spacer(Modifier.width(4.dp))
                for ((key, caption) in rankIntervals) {
                    Toggle(caption, interval == key, small = true) {
                        if (interval != key) {
                            interval = key
                            refreshing = true
                            AppPrefs.setText(context, "rank_interval", key)
                        }
                    }
                }
            }
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Palette.accent,
                    trackColor = Color.Transparent,
                )
            }
            val rows = ranked
            when {
                searched.isEmpty() -> {}
                rows == null -> PickNote("Считаю…")
                rows.filter(passes).isEmpty() -> PickNote("Ядра ещё не прислали список рынков.")
                else -> PickGrid(rows.filter(passes), byId, showCore, ranking = !byVolume, onPick = pick)
            }
        }
    }
}

/** Markets two to a row. */
@Composable
private fun PickGrid(
    rows: List<PickRow>,
    cores: Map<Long, Core>,
    showCore: Boolean,
    ranking: Boolean?,
    onPick: (Long, String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A key twice in a lazy list ends the app: a market a core lists twice is shown once.
        gridItems(rows.distinctBy { "${it.core}:${it.market}" }, key = { "${it.core}:${it.market}" }) { row ->
            PickLine(row, cores[row.core], showCore, ranking = ranking, onPick = onPick)
        }
    }
}

/** An exchange's name as its logo reads. */
internal fun exchangeTitle(core: Core): String = when (core.brand) {
    "binance" -> "Binance"
    "bybit" -> "Bybit"
    "okx" -> "OKX"
    "gate" -> "Gate"
    "bitget" -> "Bitget"
    "htx" -> "HTX"
    "hyperliquid" -> "Hyperliquid"
    else -> core.exchange.ifBlank { core.brand }
}

/**
 * The «Биржи» button of the picker: a list of the searched cores' exchanges to tick. With none
 * ticked every exchange shows; the button names how many are ticked.
 */
@Composable
private fun ExchangeFilter(
    exchanges: List<Pair<String, String>>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val active = chosen.isNotEmpty()
    Box {
        Toggle(if (active) "Биржи ${chosen.size}" else "Биржи", active) { open = true }
        MoonMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Все биржи", fontWeight = if (!active) FontWeight.Bold else FontWeight.Normal) },
                onClick = {
                    onChange(emptySet())
                    open = false
                },
            )
            if (exchanges.isEmpty()) {
                DropdownMenuItem(text = { Text("Ядра не выбраны", color = Palette.textDim) }, onClick = { open = false })
            }
            for ((brand, title) in exchanges) {
                val on = brand in chosen
                DropdownMenuItem(
                    leadingIcon = {
                        Checkbox(
                            checked = on,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                        )
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            brandLogo(brand)?.let { logo ->
                                Image(
                                    painter = imagePainter(logo),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(title)
                        }
                    },
                    onClick = {
                        val next = if (on) chosen - brand else chosen + brand
                        // Every exchange ticked is the same as none: show all.
                        onChange(if (next.size == exchanges.size) emptySet() else next)
                    },
                )
            }
        }
    }
}

/** A Spot or Futures tick of the search's filter. */
@Composable
private fun KindBox(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (on) Palette.accent else Palette.chartEdge, shape)
            .clickable { onChange(!on) }
            .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Text(if (on) "☑" else "☐", fontSize = 13.sp, color = if (on) Palette.accent else chartLabel)
        Text(" $label", fontSize = 12.sp, color = if (on) Palette.accent else chartLabel, maxLines = 1)
    }
}

@Composable
private fun PickNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.textDim, modifier = Modifier.padding(4.dp))
}

@Composable
internal fun Toggle(label: String, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (on) Palette.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (on) Palette.accent else Palette.chartEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (on) Palette.accent else chartLabel,
            maxLines = 1,
        )
    }
}

/**
 * One listed market: its name; under it the exchange's logo and, with several cores searched,
 * the core; on the right the day's volume and how far the price moved. In the ranking the figure
 * it is ranked by stands out.
 */
@Composable
private fun PickLine(row: PickRow, core: Core?, showCore: Boolean, ranking: Boolean?, onPick: (Long, String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cardBackground)
            .border(1.dp, Palette.chartEdge.copy(alpha = if (Palette.light) 0.9f else 0.55f), RoundedCornerShape(6.dp))
            .clickable { onPick(row.core, row.market) }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.market,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = Palette.chartStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logo = core?.let { brandLogo(it.brand) }
                if (logo != null) {
                    Image(
                        painter = imagePainter(logo),
                        contentDescription = core?.exchange,
                        modifier = Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(3.dp))
                }
                val caption = when {
                    core == null -> ""
                    showCore -> core.name
                    logo == null -> core.exchange
                    else -> if (core.kind == "spot") "Spot" else "Futures"
                }
                Text(
                    caption,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = Palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val volume = row.volume
            if (volume != null) {
                Text(
                    compactMoney(volume),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (ranking == false) FontWeight.Bold else FontWeight.Normal,
                    color = if (ranking == false) Palette.accent else chartLabel,
                )
            }
            val range = row.rangePct
            if (range != null || ranking != null) {
                Text(
                    if (range == null) "—" else fmt("%.2f%%", range),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (ranking == true) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        range == null -> Palette.textDim
                        (row.changePct ?: 0.0) < 0.0 -> terminalRed
                        row.changePct == null && ranking != true -> chartLabel
                        else -> terminalGreen
                    },
                )
            }
        }
    }
}

/** A day's volume the way a narrow column holds it: `12.3M`, `840K`. */
private fun compactMoney(value: Double): String = when {
    value >= 1e9 -> fmt("%.1fB", value / 1e9)
    value >= 1e6 -> fmt("%.1fM", value / 1e6)
    value >= 1e3 -> fmt("%.0fK", value / 1e3)
    else -> fmt("%.0f", value)
}
