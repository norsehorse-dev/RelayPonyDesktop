package com.relaypony.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** First run (plan section 3): welcome, language, how it works, then where to pair. */
@Composable
fun Onboarding(c: DesktopController) {
    var page by remember { mutableStateOf(0) }
    val last = 3
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    0 -> {
                        Image(painterResource("relaypony.png"), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                        Text(tr("app_name"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(tr("d_ob_welcome_body"), style = MaterialTheme.typography.bodyLarge)
                    }
                    1 -> {
                        Text(tr("ob_language_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.selectableGroup()) {
                            I18n.languages.forEach { (code, name) ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = code == I18n.lang, role = Role.RadioButton, onClick = { c.setLanguage(code) }),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = code == I18n.lang, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    2 -> {
                        Text(tr("ob_how_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(tr("d_ob_how_body"), style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> {
                        Text(tr("ob_device_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(tr("d_ob_device_body"), style = MaterialTheme.typography.bodyLarge)
                        Text(tr("ob_this_device", c.deviceName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) TextButton(onClick = { page-- }) { Text(tr("ob_back")) }
                    Spacer(Modifier.weight(1f))
                    if (page < last) TextButton(onClick = { c.finishOnboarding() }) { Text(tr("ob_skip")) }
                    Button(onClick = { if (page < last) page++ else c.finishOnboarding() }) {
                        Text(tr(if (page < last) "ob_next" else "ob_get_started"))
                    }
                }
            }
        }
    }
}
