package com.stsmn.monitor.ui

import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
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
        ScreenTitle("User", "Local profile – login nahi, kuch bhi phone se bahar nahi jata")

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        settings.profileName.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(settings.profileName.ifBlank { "Naam nahi daala" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(settings.profileRole, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(settings.profileSchool, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (editing) {
                val fieldModifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                OutlinedTextField(name, { name = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("Naam") }, singleLine = true, modifier = fieldModifier)
                OutlinedTextField(role, { role = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("Class / kaam") }, singleLine = true, modifier = fieldModifier)
                OutlinedTextField(school, { school = it.take(AppConfig.MAX_TEXT_LENGTH) }, label = { Text("School") }, singleLine = true, modifier = fieldModifier)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(name, role, school); editing = false }) { Text("Save karo") }
                    OutlinedButton(onClick = {
                        name = settings.profileName; role = settings.profileRole; school = settings.profileSchool
                        editing = false
                    }) { Text("Cancel") }
                }
            } else {
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.padding(top = 12.dp)) { Text("Profile badlo") }
            }
        }

        SectionLabel("Project ke baare me")
        AppCard {
            Text("Smart Transformer Safety & Monitoring Network", fontWeight = FontWeight.Bold)
            Text(
                "Sensors → ESP32 → Wi-Fi → ye app. ESP32 temperature, voltage, current, smoke, flame " +
                    "aur level naapta hai, 0–100 ka risk score nikalta hai aur emergency me supply khud kaat deta hai. " +
                    "Ye phone sirf monitoring dashboard hai.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionLabel("Risk score kaise banta hai")
        AppCard {
            Text(
                "Har sensor kuch points jodta hai: temperature 0–25, current 0–25, smoke 0–15, flame 0–20, oil level 0–10. " +
                    "Voltage 8 V tak safe hai, uske upar har 1 V par 20 points (12 V par 80 = EMERGENCY). " +
                    "Total 100 se zyada nahi hota. Ye saaf formula hai – machine learning nahi.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("~18 SAFE", StsmnColors.Safe)
                Pill("~55 WARNING", StsmnColors.Warning)
                Pill("90+ EMERGENCY", StsmnColors.Emergency)
            }
        }

        SectionLabel("Judges ke liye demo guide")
        AppCard {
            Text("A · Safe", fontWeight = FontWeight.Bold, color = StsmnColors.Safe)
            Text("Saari readings normal → SAFE, risk kam, relay ON.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("B · Warning", fontWeight = FontWeight.Bold, color = StsmnColors.Warning, modifier = Modifier.padding(top = 8.dp))
            Text("Ek kaabu me rakhi hui gadbad se risk badhta hai → WARNING aur event log hota hai.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("C · Emergency", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency, modifier = Modifier.padding(top = 8.dp))
            Text("Ek surakshit nakli critical input → ESP32 relay kaat deta hai, buzzer aur red LED chalu ho jaate hain.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "“Phone sirf monitoring dashboard hai. Main safety decision ESP32 par rakha hai, " +
                    "isliye app band hone par bhi local protection logic kaam karta hai.”",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
