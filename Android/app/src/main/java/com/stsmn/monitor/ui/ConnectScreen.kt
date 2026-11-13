package com.stsmn.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.data.AppSettings
import com.stsmn.monitor.domain.LiveSnapshot

@Composable
fun ConnectScreen(live: LiveSnapshot, settings: AppSettings, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = LocalBottomInset.current),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("STSMN", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Smart Transformer Safety & Monitoring Network",
            fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RadarPulse(MaterialTheme.colorScheme.primary, Modifier.size(96.dp))
                Text("Connect to ESP32", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = if (settings.useBluetooth) "${settings.deviceName}  ·  Bluetooth" else "${settings.deviceName}  ·  ${settings.deviceHost}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Device") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                Text(
                    if (settings.useBluetooth) "Bluetooth: switch it on and keep the phone near the ESP32. You do not need the ESP32 Wi-Fi." else "Join the ESP32 Wi-Fi first. Change the device address in Settings.",
                    fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppCard {
            Text(
                if (settings.useBluetooth) "Link: Bluetooth" else if (live.wifiBound) "Wi-Fi (local): available" else "Wi-Fi (local): not connected",
                fontWeight = FontWeight.SemiBold,
                color = if (settings.useBluetooth || live.wifiBound) StsmnColors.Safe else StsmnColors.Warning,
            )
            Text(
                "Monitoring is off. Nothing is shown as SAFE until a valid live packet arrives.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
