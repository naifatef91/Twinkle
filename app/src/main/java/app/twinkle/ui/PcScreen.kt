package app.twinkle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twinkle.R
import app.twinkle.data.PickedFile
import app.twinkle.ui.theme.extras
import app.twinkle.web.PcSession
import app.twinkle.web.PcMode
import app.twinkle.web.PcState
import kotlinx.coroutines.delay

/**
 * One PC-mode route on its own screen: Wi-Fi Direct, the phone's own network, or a USB-C cable. Each screen starts
 * only its own link ([PcSession.start] with [mode]) and stops it on the way out. [shared] is the current list the
 * user picked; the screen pushes it into [PcSession] whenever it changes.
 *
 * Layout (Figma "PC mode – … (redesign)"): a one-line status, one card with the numbered steps for this route, a
 * compact activity strip, and the actions pinned at the bottom. No explanatory paragraphs: the labels say what to do.
 */
@Composable
fun PcScreen(mode: PcMode, onBack: () -> Unit, onPickFiles: () -> Unit, shared: List<PickedFile>) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val state by PcSession.state.collectAsStateWithLifecycle()

    // Wi-Fi mode hosts a Wi-Fi Direct network: wait for Wi-Fi to be on (the notice below asks for it).
    val wifiOn = rememberWifiOn()
    val blocked = mode == PcMode.WIFI && !wifiOn
    LaunchedEffect(mode, blocked) { if (!blocked) PcSession.start(ctx, mode) }
    LaunchedEffect(shared) { PcSession.setShared(shared) }

    val stop = { PcSession.stop(); onBack() }
    BackHandler { stop() }

    val title = when (mode) {
        PcMode.WIFI -> R.string.pc_title_wifi
        PcMode.LAN -> R.string.pc_title_lan
        PcMode.USB -> R.string.pc_title_usb
    }

    // Wi-Fi: the network options (band, password) live behind the gear in the top bar, not in the page.
    var options by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(stringResource(title), onBack = stop) {
            if (mode == PcMode.WIFI && !blocked) IconButton(onClick = { options = true }) {
                Icon(TwinkleIcons.Settings, contentDescription = stringResource(R.string.pc_options), tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }

        val s = state
        when {
            blocked -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                RadioOffNotice(
                    TwinkleIcons.Wifi, stringResource(R.string.wifi_off_title), stringResource(R.string.pc_wifi_off_body),
                    stringResource(R.string.wifi_turn_on), onAction = { Radios.openWifi(ctx) },
                )
            }

            s is PcState.Ready && s.mode == mode -> PcPage(s, shared.size, onPickFiles, stop) {
                when (mode) {
                    PcMode.WIFI -> s.wifi?.let { WifiCard(it) }
                    PcMode.LAN -> LanCard(s.lan)
                    PcMode.USB -> UsbCard(s.usb)
                }
            }

            s is PcState.Failed && s.mode == mode -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(TwinkleIcons.Warning, contentDescription = null, tint = c.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pc_failed, s.message), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                TwinkleButton(stringResource(R.string.pc_retry), onClick = { PcSession.start(ctx, mode) }, kind = ButtonKind.Tonal, height = 52)
                Spacer(Modifier.height(10.dp))
                TwinkleButton(stringResource(R.string.pc_stop), onClick = stop, kind = ButtonKind.Outlined, height = 52)
            }

            // Starting, idle, or a different route still winding down.
            else -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (mode == PcMode.WIFI) R.string.pc_starting else R.string.pc_starting_server),
                    color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }

    val band = (state as? PcState.Ready)?.wifi?.band
    if (options && band != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { options = false },
            title = { Text(stringResource(R.string.pc_options)) },
            text = { PcOptions(band = band) },
            confirmButton = { TextButton(onClick = { options = false }) { Text(stringResource(R.string.pc_done)) } },
        )
    }
}

/** The layout every route shares: status, the route's card, activity, and the actions pinned at the bottom. */
@Composable
private fun ColumnScope.PcPage(s: PcState.Ready, sharedCount: Int, onPickFiles: () -> Unit, onStop: () -> Unit, card: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PcStatusPill(s.stats.lastSeenMs, s.stats.lastClientAddress)
        TwinkleCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = card)
        }
        PcActivityStrip(s.stats.bytesServed, s.stats.bytesReceived, s.stats.lastClientAddress)
        if (s.uploadedCount > 0) PcReceivedList(s.uploadedNames)
    }
    // Actions stay in reach at the bottom, whatever the scroll position.
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TwinkleButton(
            if (sharedCount > 0) stringResource(R.string.pc_share_count, sharedCount) else stringResource(R.string.pc_share),
            onClick = onPickFiles, leading = TwinkleIcons.ArrowUp,
        )
        TextButton(onClick = onStop) { Text(stringResource(R.string.pc_stop_short), color = c.error) }
    }
}

/** Wi-Fi Direct: ① join the phone's network, ② open the address. */
@Composable
private fun WifiCard(wifi: app.twinkle.web.WifiGroup) {
    PcStepHead(1, stringResource(R.string.pc_step_join))
    CopyField(stringResource(R.string.pc_ssid), wifi.ssid)
    CopyField(stringResource(R.string.pc_password), wifi.pass)
    PcDivider()
    PcStepHead(2, stringResource(R.string.pc_step_open))
    AddressAndQr(wifi.url, wifi.url, stringResource(R.string.pc_qr_hint))
}

/**
 * Same network: ① open the address, ② enter the PIN. Open while this screen is; the QR code carries the PIN in
 * its fragment so a scan signs in directly.
 */
@Composable
private fun LanCard(lan: app.twinkle.web.LanStatus) {
    val c = MaterialTheme.colorScheme
    val url = lan.url
    if (url == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TwinkleIcons.Warning, contentDescription = null, tint = c.error, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.pc_lan_none), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val pin = lan.pin ?: return
    PcStepHead(1, stringResource(R.string.pc_step_open))
    CopyField(stringResource(R.string.pc_address), url.removePrefix("http://"), copyValue = url, accent = true)
    PcDivider()
    PcStepHead(2, stringResource(R.string.pc_step_pin))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ltr(pin.chunked(3).joinToString(" ")), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, color = c.primary, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { PcSession.newLanPin() }) {
            Icon(TwinkleIcons.Refresh, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pc_lan_new_pin), color = c.primary)
        }
    }
    QrBlock(lan.qrUrl ?: url, stringResource(R.string.pc_lan_qr_hint2))
}

/**
 * USB-C: until tethering is up, the three steps with the ones the phone can see already ticked; then ① open the
 * address, like the other routes.
 */
@Composable
private fun UsbCard(usb: app.twinkle.web.UsbTether.Status) {
    val ctx = LocalContext.current
    val url = usb.url
    if (usb.stage == app.twinkle.web.UsbTether.Stage.READY && url != null) {
        PcStepHead(1, stringResource(R.string.pc_step_open))
        AddressAndQr(url, url, stringResource(R.string.pc_qr_hint))
        return
    }
    val done1 = usb.stage != app.twinkle.web.UsbTether.Stage.NO_CABLE
    val done2 = usb.fileTransfer || usb.stage == app.twinkle.web.UsbTether.Stage.STARTING
    val current = when { !done1 -> 1; !done2 -> 2; else -> 3 }
    PcStepHead(1, stringResource(R.string.pc_usb_s1), stateOf(1, current, done1))
    PcStepHead(2, stringResource(R.string.pc_usb_s2), stateOf(2, current, done2))
    PcStepHead(3, stringResource(R.string.pc_usb_s3), stateOf(3, current, false))
    val failed = stringResource(R.string.pc_usb_settings_failed)
    Spacer(Modifier.height(2.dp))
    TwinkleButton(
        stringResource(R.string.pc_usb_open_settings), kind = ButtonKind.Tonal, height = 48,
        onClick = { if (!app.twinkle.web.UsbTether.openTetherSettings(ctx)) android.widget.Toast.makeText(ctx, failed, android.widget.Toast.LENGTH_LONG).show() },
    )
}

private enum class StepState { DONE, CURRENT, TODO }

private fun stateOf(n: Int, current: Int, done: Boolean) = when { done -> StepState.DONE; n == current -> StepState.CURRENT; else -> StepState.TODO }

@Composable
private fun PcDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
}

/** Address (without "http://", so it fits one line; copy still gives the full URL) and the QR code under it. */
@Composable
private fun AddressAndQr(url: String, qrText: String, caption: String) {
    CopyField(stringResource(R.string.pc_address), url.removePrefix("http://"), copyValue = url, accent = true)
    QrBlock(qrText, caption)
}

@Composable
private fun QrBlock(qrText: String, caption: String) {
    val c = MaterialTheme.colorScheme
    val qr = remember(qrText) { qrBitmap(qrText, sizePx = 512) }
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) { Image(qr.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize()) }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** One-line status: amber while waiting, green with the computer's address once it is talking to the phone. */
@Composable
private fun PcStatusPill(lastSeenMs: Long, lastClient: String?) {
    val x = MaterialTheme.extras
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val connected = lastSeenMs > 0 && now - lastSeenMs < 8_000
    val fg = if (connected) x.ok else x.warn
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (connected) x.okSoft else x.warnSoft).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(fg))
        Text(
            if (connected) stringResource(R.string.pc_connected, ltr(lastClient ?: "")) else stringResource(R.string.pc_wait_short),
            style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PcStepHead(n: Int, label: String, state: StepState = StepState.CURRENT) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(
                when (state) { StepState.DONE -> x.ok; StepState.CURRENT -> c.primary; StepState.TODO -> c.outline },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.DONE) Icon(TwinkleIcons.Check, contentDescription = null, tint = c.surface, modifier = Modifier.size(14.dp))
            else Text(n.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (state == StepState.TODO) c.onSurfaceVariant else c.onPrimary)
        }
        Text(
            label, style = MaterialTheme.typography.titleMedium,
            fontWeight = if (state == StepState.TODO) FontWeight.Normal else FontWeight.SemiBold,
            color = if (state == StepState.TODO) c.onSurfaceVariant else c.onSurface,
        )
    }
}

/** Label over a value, with a copy icon; [copyValue] is what goes to the clipboard when it differs from what is shown. */
@Composable
private fun CopyField(label: String, value: String, copyValue: String = value, accent: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val copied = stringResource(R.string.pc_copied)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Text(
                ltr(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                color = if (accent) c.primary else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(copyValue))
            android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
        }) { Icon(TwinkleIcons.Copy, contentDescription = stringResource(R.string.pc_copy), tint = c.primary, modifier = Modifier.size(20.dp)) }
    }
}

/** Sent, received and last device in one compact card. */
@Composable
private fun PcActivityStrip(served: Long, received: Long, client: String?) {
    val c = MaterialTheme.colorScheme
    TwinkleCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PcStat(TwinkleIcons.ArrowUp, formatBytes(served), stringResource(R.string.pc_stat_sent), c.onSurface, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(28.dp).background(c.outline))
            PcStat(TwinkleIcons.ArrowDown, formatBytes(received), stringResource(R.string.pc_stat_received), c.primary, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(28.dp).background(c.outline))
            PcStat(TwinkleIcons.Monitor, client ?: "—", stringResource(R.string.pc_stats_client), c.onSurface, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PcStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, color: Color, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(ltr(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Wraps [s] in a left-to-right isolate: in the Arabic layout "678 742" would otherwise be laid out as "742 678",
 * while the line keeps its right-hand alignment.
 */
private fun ltr(s: String) = "⁦$s⁩"
