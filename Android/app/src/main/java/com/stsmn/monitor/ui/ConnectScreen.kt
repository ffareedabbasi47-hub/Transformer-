package com.stsmn.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

/**
 * Mockup screen 2: "Connect to ESP32". Shown on Home only while monitoring is off and nothing has been received yet.
 * The device address itself is edited in Settings (validated there: private IPv4 only).
 */
@Composable
fun ConnectScreen(live: LiveSnapshot, settings: AppSettings, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
                Text("📶", fontSize = 40.sp)
                Text("Connect to ESP32", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = "${settings.deviceName}  ·  ${settings.deviceHost}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Device") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                Text(
                    "Join the ESP32 Wi-Fi first. Change the device address in Settings.",
                    fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppCard {
            Text(
                if (live.wifiBound) "Wi-Fi (local): available" else "Wi-Fi (local): not connected",
                fontWeight = FontWeight.SemiBold,
                color = if (live.wifiBound) StsmnColors.Safe else StsmnColors.Warning,
            )
            Text(
                "Monitoring is off. Nothing is shown as SAFE until a valid live packet arrives.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
