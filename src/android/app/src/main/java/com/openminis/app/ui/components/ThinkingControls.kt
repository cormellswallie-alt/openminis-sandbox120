package com.openminis.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.ThinkingDefaults
import com.openminis.app.data.ThinkingControlPolicy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.localizedName
import kotlin.math.roundToInt

@Composable
fun ThinkingLevelSlider(
    current: ThinkingLevel,
    available: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val levels = remember(available) { ThinkingControlPolicy.levels(available) }
    val selected = levels.indexOf(current).takeIf { it >= 0 }
        ?: levels.indexOfLast { it.rank <= current.rank }.coerceAtLeast(0)
    var position by remember(current, levels) { mutableFloatStateOf(selected.toFloat()) }
    val pending = levels[position.roundToInt().coerceIn(levels.indices)]
    Column(modifier) {
        Text(pending.localizedName(context), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onSelect(pending) },
            valueRange = 0f..(levels.size - 1).coerceAtLeast(1).toFloat(),
            steps = (levels.size - 2).coerceAtLeast(0),
            enabled = levels.size > 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(levels.first().localizedName(context), style = MaterialTheme.typography.labelSmall)
            Text(levels.last().localizedName(context), style = MaterialTheme.typography.labelSmall)
        }
        if (levels.size == 1) {
            Text(stringResource(R.string.thinking_slider_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun GlobalThinkingSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ThinkingDefaults.load(context) }
    val current by ThinkingDefaults.level.collectAsState()
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.thinking_global_title),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        ThinkingLevelSlider(current, ThinkingLevel.entries, { ThinkingDefaults.set(context, it) })
        Text(stringResource(R.string.thinking_global_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}
