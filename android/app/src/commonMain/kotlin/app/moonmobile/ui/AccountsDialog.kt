package app.moonmobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.Session
import app.moonmobile.json.JSONArray
import app.moonmobile.json.JSONObject
import kotlinx.coroutines.delay

/** One coin of a wallet: its whole amount, what stands in orders included, and its worth. */
internal data class WalletAsset(
    /** `coin`, or `cash` for the wallet's own currency. */
    val kind: String,
    val coin: String,
    val amount: Double,
    /** In USDT; USDT and USDC at one dollar. */
    val value: Double?,
)

/** One wallet of an exchange account: spot, futures, coin-margined, or a single shared one. */
internal data class Wallet(
    val kind: String,
    val currency: String,
    val cores: List<String>,
    /** `live`, `stale`, `awaiting` or `unpriced`. */
    val state: String,
    val total: Double?,
    val assets: List<WalletAsset>,
    val smallCount: Int,
    val smallUsdt: Double,
)

/** One exchange account, however many cores trade on it, with its wallets. */
internal data class ExchangeAccount(
    /** Stable across launches, for the name the user gives it. */
    val key: String,
    val exchange: String,
    val brand: String,
    /** Its place among the exchange's accounts, from 1; 0 for the only one, or an unknown one. */
    val number: Int,
    val known: Boolean,
    val total: Double?,
    val wallets: List<Wallet>,
)

internal data class Accounts(
    val total: Double?,
    val accounts: List<ExchangeAccount>,
    val excluded: List<String>,
)

private fun JSONObject.num(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseAccounts(json: JSONObject): Accounts {
    val accounts = json.optJSONArray("accounts").objects().map { a ->
        ExchangeAccount(
            key = a.optString("key"),
            exchange = a.optString("exchange"),
            brand = a.optString("brand"),
            number = a.optInt("number"),
            known = a.optBoolean("known"),
            total = a.num("total"),
            wallets = a.optJSONArray("wallets").objects().map { w ->
                Wallet(
                    kind = w.optString("kind"),
                    currency = w.optString("currency"),
                    cores = w.optJSONArray("cores").strings(),
                    state = w.optString("state"),
                    total = w.num("total"),
                    assets = w.optJSONArray("assets").objects().map { r ->
                        WalletAsset(
                            kind = r.optString("kind"),
                            coin = r.optString("coin"),
                            amount = r.num("amount") ?: 0.0,
                            value = r.num("value"),
                        )
                    },
                    smallCount = w.optInt("small_count"),
                    smallUsdt = w.num("small_usdt") ?: 0.0,
                )
            },
        )
    }
    return Accounts(json.num("total"), accounts, json.optJSONArray("excluded").strings())
}

private fun walletTitle(kind: String): String = when (kind) {
    "spot" -> "Спот"
    "futures" -> "Фьючерсы"
    "quarterly" -> "Фьючерсы COIN-M"
    "unified" -> "Единый счёт"
    else -> "Счёт"
}

/** Where the names the user gives exchange accounts are kept, by the account's key. */
private fun nameKey(account: ExchangeAccount) = "account_name:${account.key}"

/** The account as listed: its exchange, then the user's name for it, or its number. */
private fun accountTitle(account: ExchangeAccount, name: String): String {
    val exchange = account.exchange.ifBlank { exchangeTitle(account.brand) }.ifBlank { "Биржа" }
    val second = when {
        name.isNotBlank() -> name
        !account.known -> "аккаунт не определён"
        account.number > 0 -> "аккаунт ${account.number}"
        else -> ""
    }
    return if (second.isEmpty()) exchange else "$exchange · $second"
}

/** What a hidden amount is drawn as. */
private const val HIDDEN = "••••"

/**
 * The total balance taken apart by exchange account: one block per account with what all its
 * wallets hold; a tap opens the wallets (spot, futures, coin-margined) with their coins — what
 * stands in orders included — and what they are worth, a long press names the account. Cores
 * trading one account are one block (the bridge groups them as the total does), so nothing is
 * counted twice.
 */
@Composable
internal fun AccountsDialog(hidden: Boolean, onDismiss: () -> Unit) {
    val context = platformContext()
    var accounts by remember { mutableStateOf<Accounts?>(null) }
    var failed by remember { mutableStateOf("") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val names = remember { mutableStateMapOf<String, String>() }
    var renaming by remember { mutableStateOf<ExchangeAccount?>(null) }
    LaunchedEffect(Unit) {
        var refresh = true
        while (true) {
            val reply = Session.call(JSONObject().put("op", "accounts").put("refresh", refresh))
            refresh = false
            if (reply.ok) {
                val parsed = parseAccounts(reply.json)
                for (account in parsed.accounts) {
                    if (account.key !in names) names[account.key] = AppPrefs.text(context, nameKey(account), "")
                }
                accounts = parsed
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        for (account in data.accounts) {
                            AccountBlock(
                                account = account,
                                title = accountTitle(account, names[account.key].orEmpty()),
                                expanded = open[account.key] == true,
                                hidden = hidden,
                                money = ::money,
                                onToggle = { open[account.key] = open[account.key] != true },
                                onRename = { renaming = account },
                            )
                        }
                        if (data.excluded.isNotEmpty()) {
                            Text(
                                "Не входят в сумму: ${data.excluded.joinToString(", ")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.textDim,
                            )
                        }
                        Text(
                            "Нажмите на аккаунт, чтобы раскрыть; удерживайте, чтобы назвать.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.textDim,
                        )
                    }
                }
            }
        },
    )
    renaming?.let { account ->
        RenameAccountDialog(
            current = names[account.key].orEmpty(),
            placeholder = accountTitle(account, ""),
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                names[account.key] = name
                AppPrefs.setText(context, nameKey(account), name)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountBlock(
    account: ExchangeAccount,
    title: String,
    expanded: Boolean,
    hidden: Boolean,
    money: (Double?) -> String,
    onToggle: () -> Unit,
    onRename: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surfaceHigh, RoundedCornerShape(10.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onRename)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.titleSmall,
                color = Palette.textDim,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
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
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (wallet in account.wallets) WalletBlock(wallet, hidden, money)
            }
        }
    }
}

@Composable
private fun WalletBlock(wallet: Wallet, hidden: Boolean, money: (Double?) -> String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surface, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                walletTitle(wallet.kind),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("${money(wallet.total)} USDT", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        when (wallet.state) {
            "stale" -> "данные до потери связи"
            "awaiting" -> "ждём баланс"
            "unpriced" -> "нет курса к USDT"
            else -> null
        }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.amber) }
        if (wallet.assets.isEmpty() && wallet.smallCount == 0) {
            Text(
                "Пусто",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.textDim,
            )
        }
        for (asset in wallet.assets) AssetLine(asset, hidden, money)
        if (wallet.smallCount > 0) {
            val worth = if (wallet.smallUsdt > 0.0) " на ${money(wallet.smallUsdt)} USDT" else ""
            Text(
                "ещё мелких монет: ${wallet.smallCount}$worth",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textDim,
            )
        }
    }
}

@Composable
private fun AssetLine(asset: WalletAsset, hidden: Boolean, money: (Double?) -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(asset.coin, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(
            if (hidden) HIDDEN else formatPrice(asset.amount),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            asset.value?.let { "${money(it)} $" } ?: "без цены",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RenameAccountDialog(current: String, placeholder: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    MoonDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название аккаунта") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onSave("") }) { Text("Как было") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
