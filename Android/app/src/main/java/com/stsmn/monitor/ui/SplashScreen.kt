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
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF070E1C)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(112.dp).clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF1B63C9).copy(alpha = 0.25f))
                    .border(2.dp, StsmnColors.Info, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("⚡", fontSize = 56.sp) }
            Text("STSMN", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 2.sp)
            Text(
                "Smart Transformer\nSafety & Monitoring Network",
                fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
        }
        Text(
            "Safer Transformers\nBrighter Tomorrow",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}
