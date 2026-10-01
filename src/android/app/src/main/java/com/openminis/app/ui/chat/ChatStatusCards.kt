package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.theme.ChatColors

internal const val STATUS_TRANSITION_MS = 160

@Composable
internal fun StatusExpansion(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(STATUS_TRANSITION_MS)) + expandVertically(tween(STATUS_TRANSITION_MS), expandFrom = Alignment.Top),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Top),
    ) { content() }
}

@Composable
internal fun toolStateLabel(state: ToolPresentationState): String = stringResource(when (state) {
    ToolPresentationState.PREPARING -> R.string.feature_ui_preparing
    ToolPresentationState.RUNNING -> R.string.feature_ui_running
    ToolPresentationState.SUCCEEDED -> R.string.feature_ui_success
    ToolPresentationState.FAILED -> R.string.feature_ui_failed
    ToolPresentationState.TIMED_OUT -> R.string.feature_ui_timeout
    ToolPresentationState.STOPPED -> R.string.feature_ui_stopped
})

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
) {
    val state = toolPresentationState(block.toolStatus)
    val active = state == ToolPresentationState.PREPARING || state == ToolPresentationState.RUNNING
    val label = toolStateLabel(state)
    val colors = MaterialTheme.colorScheme
    val stateColor by animateColorAsState(
        targetValue = when (state) {
            ToolPresentationState.SUCCEEDED -> if (ChatColors.isDark) Color(0xFF91B09A) else Color(0xFF386345)
            ToolPresentationState.FAILED, ToolPresentationState.TIMED_OUT -> colors.error
            ToolPresentationState.STOPPED -> ChatColors.secondaryText
            else -> colors.primary
        }, animationSpec = tween(STATUS_TRANSITION_MS), label = "toolStateColor",
    )
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    var menu by remember(block.id) { mutableStateOf(false) }
    val expandLabel = stringResource(if (expanded) R.string.feature_ui_collapse else R.string.feature_ui_expand)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(STATUS_TRANSITION_MS), label = "toolChevron")
    val shape = RoundedCornerShape(12.dp)
    // No size animation on streaming payload: only the explicit expand toggle animates.
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
        .background(ChatColors.toolCapsuleBg).border(0.5.dp, ChatColors.toolBorder, shape)) {
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { stateDescription = "$label · $expandLabel" }
                    .combinedClickable(
                        onClickLabel = expandLabel,
                        onClick = { expanded = !expanded },
                        onLongClick = if (onRerunFromHere != null || onCopyDetails != null) ({ menu = true }) else null,
                    ).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(toolIconFor(block.toolName), null, tint = stateColor, modifier = Modifier.size(16.dp))
                Text(
                    helperControlSummary(block).takeIf { com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName) }
                        ?: block.toolTitle.ifEmpty { block.toolName },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(label, color = stateColor, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = ChatColors.secondaryText,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
                if (active && onStop != null) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.StopCircle, stringResource(R.string.stop_tool), tint = colors.error, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                }
            }
            MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onRerunFromHere?.let { action ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                        onClick = { menu = false; action() }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                }
                onCopyDetails?.let { action ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                        onClick = { menu = false; action() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                }
            }
        }
        StatusExpansion(expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = ChatColors.toolBorder)
                if (block.toolArgs.isNotBlank()) {
                    ToolPreview(stringResource(R.string.feature_ui_tool_input), block.toolArgs)
                }
                if (block.content.isNotBlank()) {
                    ToolPreview(stringResource(R.string.feature_ui_tool_output), block.content)
                }
                if (block.durationMs > 0 && !active) {
                    Text("${block.durationMs / 1000.0}s", style = MaterialTheme.typography.labelSmall, color = ChatColors.secondaryText)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onOpenDetail(block.id) }) { Text(stringResource(R.string.feature_ui_details)) }
                    onRetry?.let { action -> TextButton(onClick = action) { Text(stringResource(R.string.chat_longpress_retry)) } }
                    onRerunFromHere?.let { action -> TextButton(onClick = action) { Text(stringResource(R.string.tool_longpress_rerun_from_here)) } }
                }
            }
        }
        // A cancelled last tool already has a valid retry callback. Make it visible when folded too.
        if (!expanded && onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.chat_longpress_retry))
            }
        }
    }
}

@Composable
private fun ToolPreview(title: String, content: String) {
    val preview = remember(content) { boundedToolPreview(content) }
    Text(title, color = ChatColors.secondaryText, style = MaterialTheme.typography.labelSmall)
    Text(preview, color = ChatColors.primaryText, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
}

/** Stable composer chrome; it remains discoverable when the marker scrolls away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactRecoveryActions(
    undoAvailable: Boolean,
    redoAvailable: Boolean,
    streaming: Boolean,
    compacting: Boolean,
    restoring: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    StatusExpansion(undoAvailable || redoAvailable || restoring) {
        Column(Modifier.fillMaxWidth()) {
            if (restoring) {
                Text(stringResource(R.string.feature_ui_compact_restoring),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (undoAvailable) {
                    TextButton(onClick = onUndo, enabled = compactActionsEnabled(streaming, compacting, restoring)) {
                        Icon(Icons.Default.Undo, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feature_ui_undo_compact))
                    }
                }
                if (redoAvailable) {
                    TextButton(onClick = onRedo, enabled = compactActionsEnabled(streaming, compacting, restoring)) {
                        Icon(Icons.Default.Redo, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feature_ui_redo_compact))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkWaitingCard(waiting: Boolean, onStop: () -> Unit, onRetry: () -> Unit) {
    StatusExpansion(waiting) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow)
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WifiOff, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.feature_ui_wait_network), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.feature_ui_wait_network_hint),
                style = MaterialTheme.typography.bodySmall, color = ChatColors.secondaryText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.feature_ui_stop_waiting)) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.feature_ui_retry_now)) }
            }
        }
    }
}
