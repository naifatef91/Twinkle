package app.twinkle.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.twinkle.ui.theme.extras

/**
 * Wi-Fi and Bluetooth on/off, live. Transfers between phones need Wi-Fi switched on (Wi-Fi Direct runs on the
 * Wi-Fi radio even with no network joined); finding nearby phones needs Bluetooth. The screens check these up
 * front and say so, instead of failing later with a connection error.
 */
object Radios {
    fun wifiOn(context: Context): Boolean =
        runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled }.getOrDefault(true)

    fun bluetoothOn(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true }.getOrDefault(false)

    /** The system's Wi-Fi panel over the app (Android 10+), so the user flips the switch without leaving. */
    fun openWifi(context: Context) {
        val tries = listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    /** Asks the system to turn Bluetooth on (its own yes/no dialog), falling back to the Bluetooth settings page. */
    fun openBluetooth(context: Context) {
        val tries = listOf(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** True while Wi-Fi is switched on; follows the system broadcast so the screen reacts when the user turns it on. */
@Composable
fun rememberWifiOn(): Boolean = rememberRadioState(WifiManager.WIFI_STATE_CHANGED_ACTION) { Radios.wifiOn(it) }

/** True while Bluetooth is switched on. */
@Composable
fun rememberBluetoothOn(): Boolean = rememberRadioState(BluetoothAdapter.ACTION_STATE_CHANGED) { Radios.bluetoothOn(it) }

@Composable
private fun rememberRadioState(action: String, read: (Context) -> Boolean): Boolean {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(read(ctx)) }
    DisposableEffect(action) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { on = read(c) }
        }
        // Both are protected system broadcasts, so the receiver can stay not-exported.
        androidx.core.content.ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        on = read(ctx)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return on
}

/** Full-page notice when a radio the screen needs is off: icon, one line of what, one of why, and the switch. */
@Composable
fun RadioOffNotice(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val x = MaterialTheme.extras
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(x.warnSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = x.warn, modifier = Modifier.size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        TwinkleButton(action, onAction, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Compact one-line version for screens that still work without the radio (e.g. QR scan instead of Bluetooth search). */
@Composable
fun RadioOffBanner(icon: ImageVector, text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val x = MaterialTheme.extras
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(x.warnSoft).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = x.warn, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = x.warn, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = x.warn, fontWeight = FontWeight.SemiBold) }
    }
}
