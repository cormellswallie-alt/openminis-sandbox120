package com.openminis.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/** A static activity mark. Status changes carry motion, activity does not loop. */
@Composable
internal fun BouncingDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { Box(Modifier.size(3.dp).background(color, CircleShape)) }
    }
}

@Composable
internal fun StreamingDotsText() {
    Text("…", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // This updates useful elapsed-time data once per second, not animation frames.
    // LaunchedEffect is cancelled as soon as the progress item leaves composition.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (progress.depth > 0) {
                stringResource(R.string.compact_progress_split, elapsedSec, progress.callsIssued, progress.callBudget)
            } else {
                stringResource(R.string.compact_progress, elapsedSec)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ChatColors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
internal fun TypingIndicator() {
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis" }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) { opacity.animateTo(1f, tween(STATUS_TRANSITION_MS)) }
    Row(
        Modifier.padding(top = 2.dp, bottom = 8.dp).graphicsLayer { alpha = opacity.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.chat_typing_indicator, soulName),
            style = MaterialTheme.typography.bodyMedium, color = ChatColors.secondaryText)
        Spacer(Modifier.width(6.dp))
        BouncingDots(ChatColors.secondaryText)
    }
}
