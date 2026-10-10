package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.data.AppSettings

@Composable
fun UserScreen(settings: AppSettings, onSave: (name: String, role: String, school: String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(settings.profileName) }
    var role by rememberSaveable { mutableStateOf(settings.profileRole) }
    var school by rememberSaveable { mutableStateOf(settings.profileSchool) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("User", "Local profile – no login, nothing leaves this phone")

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        settings.profileName.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(settings.profileName.ifBlank { "Unnamed" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(settings.profileRole, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(settings.profileSchool, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editing) {
                val fieldModifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                OutlinedTextField(name, { name = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("Name") }, singleLine = true, modifier = fieldModifier)
                OutlinedTextField(role, { role = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("Class / role") }, singleLine = true, modifier = fieldModifier)
                OutlinedTextField(school, { school = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("School") }, singleLine = true, modifier = fieldModifier)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(name, role, school); editing = false }) { Text("Save") }
                    OutlinedButton(onClick = {
                        name = settings.profileName; role = settings.profileRole; school = settings.profileSchool
                        editing = false
                    }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.padding(top = 12.dp)) { Text("Edit profile") }
            }
        }

        SectionLabel("About the project")
        AppCard {
            Text("Smart Transformer Safety & Monitoring Network", fontWeight = FontWeight.Bold)
            Text(
                "Sensors → ESP32 → Wi-Fi → this app. The ESP32 measures temperature, voltage, current, smoke, flame " +
                    "and level, calculates a 0–100 risk score and cuts the supply by itself in an emergency. " +
                    "This phone is only the monitoring dashboard.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionLabel("How the risk score works")
        AppCard {
            Text(
                "Each sensor adds a bounded number of points: temperature 0–25, current 0–25, voltage 0–15, smoke 0–15, " +
                    "flame 0–20, oil level 0–10. The total is limited to 100. It is a transparent formula – not machine learning.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("~18 SAFE", StsmnColors.Safe)
                Pill("~55 WARNING", StsmnColors.Warning)
                Pill("90+ EMERGENCY", StsmnColors.Emergency)
            }
        }

        SectionLabel("Demo guide for judges")
        AppCard {
            Text("A · Safe", fontWeight = FontWeight.Bold, color = StsmnColors.Safe)
            Text("All readings normal → SAFE, low risk, relay ON.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("B · Warning", fontWeight = FontWeight.Bold, color = StsmnColors.Warning, modifier = Modifier.padding(top = 8.dp))
            Text("A controlled abnormal value raises risk → WARNING and an event is logged.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("C · Emergency", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency, modifier = Modifier.padding(top = 8.dp))
            Text("A safe simulated critical input → ESP32 cuts the relay, buzzer + red LED turn on.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "“Phone sirf monitoring dashboard hai. Main safety decision ESP32 par rakha hai, " +
                    "isliye app band hone par bhi local protection logic kaam karta hai.”",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
