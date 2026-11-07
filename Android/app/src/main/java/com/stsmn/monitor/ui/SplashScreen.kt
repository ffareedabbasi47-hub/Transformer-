package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mockup screen 1: shown for ~1.3 s on launch. Pure UI, no data access. */
@Composable
fun SplashScreen() {
    val gold = AccentChoice.GOLD.color
    Box(
        Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFF111315))
                    .border(1.dp, gold.copy(alpha = 0.7f), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("⚡", fontSize = 48.sp, color = gold) }
            Text("STSMN", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF1EEE5), letterSpacing = 6.sp)
            Box(Modifier.size(width = 44.dp, height = 2.dp).background(gold))
            Text(
                "Smart Transformer\nSafety & Monitoring Network",
                fontSize = 14.sp, color = Color(0xFFA7A39B), textAlign = TextAlign.Center, letterSpacing = 0.4.sp,
            )
        }
        Text(
            "AURELION  ·  Safer Transformers, Brighter Tomorrow",
            fontSize = 11.sp, color = gold.copy(alpha = 0.8f), textAlign = TextAlign.Center, letterSpacing = 1.4.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}
