package com.relaypony.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val BrandViolet = Color(0xFF5A4FE0)
val BrandCyan = Color(0xFF1E97A8)
val BrandGradient = Brush.linearGradient(listOf(BrandViolet, BrandCyan))

/** The Pony-family look: violet→cyan, used as a signal on the moments that matter. */
@Composable
fun RelayPonyTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = BrandViolet, secondary = BrandCyan, tertiary = BrandCyan)
    } else {
        lightColorScheme(primary = BrandViolet, secondary = BrandCyan, tertiary = BrandCyan)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The gradient wordmark bar across the top of the window. */
@Composable
fun GradientHeader() {
    Box(Modifier.fillMaxWidth().height(58.dp).background(BrandGradient)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource("relaypony.png"),
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text("RelayPony", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("encrypted · direct · no cloud", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The broadcast radar: concentric rings pulse out from a gradient core while discoverable. */
@Composable
fun RadarPulse(active: Boolean, modifier: Modifier = Modifier.size(132.dp)) {
    val transition = rememberInfiniteTransition()
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier) {
        val maxR = size.minDimension / 2f
        if (active) {
            for (i in 0..2) {
                val p = (t + i / 3f) % 1f
                drawCircle(color = BrandViolet.copy(alpha = (1f - p) * 0.45f), radius = maxR * p, style = Stroke(width = 3f), center = center)
            }
        }
        drawCircle(brush = BrandGradient, radius = maxR * 0.22f, center = center)
    }
}

/** A glanceable trust marker: a filled gradient badge when paired, a quiet ring when not. */
@Composable
fun TrustBadge(paired: Boolean) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (paired) BrandGradient else Brush.linearGradient(listOf(Color(0x33888888), Color(0x33888888)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (paired) "✓" else "?", color = if (paired) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
    }
}
