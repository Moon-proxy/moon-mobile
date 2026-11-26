package app.moonmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.Session
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import kotlinx.coroutines.delay

/** One coin or position of an account. */
internal data class AccountAsset(
    /** `coin`, `cash` (the account's own currency) or `position`. */
    val kind: String,
    val coin: String,
    val market: String,
    val amount: Double,
    val value: Double?,
    /** `long` or `short`, for a position. */
    val side: String?,
    val pnl: Double?,
)

/** One exchange account, however many cores trade on it. */
internal data class AccountEntry(
    val exchange: String,
    val brand: String,
    val kind: String,
    val currency: String,
    val cores: List<String>,
    /** `live`, `stale`, `awaiting` or `unpriced`. */
    val state: String,
    val total: Double?,
    val free: Double?,
    val pnl: Double?,
    val assets: List<AccountAsset>,
    val smallCount: Int,
    val smallUsdt: Double,
)

internal data class Accounts(
    val total: Double?,
    val free: Double?,
    val accounts: List<AccountEntry>,
    val excluded: List<String>,
)

private fun JSONObject.num(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

internal fun parseAccounts(json: JSONObject): Accounts {
    val list = json.optJSONArray("accounts")
    val accounts = (0 until (list?.length() ?: 0)).mapNotNull { at ->
        val o = list?.optJSONObject(at) ?: return@mapNotNull null
        val rows = o.optJSONArray("assets")
        AccountEntry(
            exchange = o.optString("exchange"),
            brand = o.optString("brand"),
            kind = o.optString("kind"),
            currency = o.optString("currency"),
            cores = o.optJSONArray("cores").strings(),
            state = o.optString("state"),
            total = o.num("total"),
            free = o.num("free"),
            pnl = o.num("pnl"),
            assets = (0 until (rows?.length() ?: 0)).mapNotNull { r ->
                val a = rows?.optJSONObject(r) ?: return@mapNotNull null
                AccountAsset(
                    kind = a.optString("kind"),
                    coin = a.optString("coin"),
                    market = a.optString("market"),
                    amount = a.num("amount") ?: 0.0,
                    value = a.num("value"),
                    side = a.optString("side").takeIf { it.isNotBlank() },
                    pnl = a.num("pnl"),
                )
            },
            smallCount = o.optInt("small_count"),
            smallUsdt = o.num("small_usdt") ?: 0.0,
        )
    }
    return Accounts(json.num("total"), json.num("free"), accounts, json.optJSONArray("excluded").strings())
}

private fun kindTitle(kind: String): String = when (kind) {
    "spot" -> "Спот"
    "futures" -> "Фьючерсы"
    "quarterly" -> "Квартальные"
    "unified" -> "единый счёт"
    else -> ""
}

/** What a hidden amount is drawn as. */
private const val HIDDEN = "••••"

/**
 * The total balance taken apart: each exchange account once, with its balance, coins and
 * positions. Cores trading one account are one entry (the bridge groups them as the total does),
 * so nothing is counted twice and the entries add up to the total.
 */
@Composable
internal fun AccountsDialog(hidden: Boolean, onDismiss: () -> Unit) {
    var accounts by remember { mutableStateOf<Accounts?>(null) }
    var failed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var refresh = true
        while (true) {
            val reply = Session.call(JSONObject().put("op", "accounts").put("refresh", refresh))
            refresh = false
            if (reply.ok) {
                accounts = parseAccounts(reply.json)
                failed = ""
            } else if (accounts == null) {
                failed = reply.message.ifBlank { "Нет ответа" }
            }
            delay(3_000L)
        }
    }
    fun money(value: Double?): String = when {
        hidden -> HIDDEN
        value == null -> "—"
        else -> formatAmount(value)
    }
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text("Балансы бирж") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        text = {
            val data = accounts
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    data == null && failed.isNotEmpty() -> Text(failed, color = Palette.red)
                    data == null -> Text("Загружаю…", color = Palette.textDim)
                    data.accounts.isEmpty() -> Text("Ядра ещё не прислали балансы.", color = Palette.textDim)
                    else -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Всего", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(
                                "${money(data.total)} USDT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (data.free != null && !hidden) {
                            Text(
                                "свободно ${formatAmount(data.free)} USDT",
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.textDim,
                            )
                        }
                        for (account in data.accounts) AccountBlock(account, hidden, ::money)
                        if (data.excluded.isNotEmpty()) {
                            Text(
                                "Не входят в сумму: ${data.excluded.joinToString(", ")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.textDim,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AccountBlock(account: AccountEntry, hidden: Boolean, money: (Double?) -> String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surfaceHigh, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = listOf(account.exchange.ifBlank { exchangeTitle(account.brand) }, kindTitle(account.kind))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                title.ifBlank { "Биржа не известна" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${money(account.total)} USDT",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        val notes = buildList {
            if (account.free != null) add("свободно ${money(account.free)}")
            account.pnl?.takeIf { it != 0.0 }?.let { add("PnL ${if (hidden) HIDDEN else signed(it)}") }
            when (account.state) {
                "stale" -> add("данные до потери связи")
                "awaiting" -> add("ждём баланс")
                "unpriced" -> add("нет курса к USDT")
            }
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
        }
        Text(
            (if (account.cores.size > 1) "Ядра (${account.cores.size}): " else "Ядро: ") + account.cores.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.textDim,
        )
        if (account.assets.isEmpty() && account.smallCount == 0) {
            Text(
                when (account.kind) {
                    "futures", "quarterly" -> "Открытых позиций нет"
                    "unified" -> "Монет и позиций нет"
                    else -> "Монет нет"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Palette.textDim,
            )
        }
        for (asset in account.assets) AssetLine(asset, hidden, money)
        if (account.smallCount > 0) {
            val worth = if (account.smallUsdt > 0.0) " на ${money(account.smallUsdt)} USDT" else ""
            Text(
                "ещё мелких монет: ${account.smallCount}$worth",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textDim,
            )
        }
    }
}

@Composable
private fun AssetLine(asset: AccountAsset, hidden: Boolean, money: (Double?) -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(asset.coin, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (asset.kind == "position") {
            Spacer(Modifier.width(6.dp))
            val short = asset.side == "short"
            Text(
                if (short) "Short" else "Long",
                style = MaterialTheme.typography.labelMedium,
                color = if (short) Palette.red else Palette.green,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (hidden) HIDDEN else formatPrice(asset.amount),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                asset.value?.let { "${money(it)} $" } ?: "без цены",
                style = MaterialTheme.typography.bodyMedium,
            )
            asset.pnl?.let {
                Text(
                    if (hidden) HIDDEN else signed(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it < 0.0) Palette.red else Palette.green,
                )
            }
        }
    }
}

private fun signed(value: Double): String = (if (value > 0.0) "+" else "") + formatAmount(value)
