package com.openminis.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.openai.NativeWebSearchProbe
import com.openminis.app.provider.openai.NativeWebSearchProbeResult
import com.openminis.app.ui.chat.StreamingMarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
fun NativeWebSearchTestPanel(entry: ModelEntry, repository: ProviderRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember(entry.id) { mutableStateOf(false) }
    var result by remember(entry.id) { mutableStateOf<NativeWebSearchProbeResult?>(null) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.web_search_probe_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        MinisOutlinedButton(
            enabled = !running,
            onClick = {
                scope.launch {
                    running = true; result = null; error = null
                    try {
                        result = NativeWebSearchProbe.run(context, entry, repository)
                    } catch (e: TimeoutCancellationException) {
                        error = context.getString(R.string.web_search_probe_timeout)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        error = e.message ?: context.getString(R.string.web_search_probe_failed)
                    } finally { running = false }
                }
            },
        ) { Text(stringResource(R.string.web_search_probe_action)) }
        if (running) {
            Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.web_search_probe_running), style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        result?.let { evidence ->
            Text(
                text = stringResource(if (evidence.verified) R.string.web_search_probe_success else R.string.web_search_probe_unverified),
                color = if (evidence.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (evidence.verified) {
                Text(stringResource(R.string.web_search_probe_calls, evidence.completedCalls),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (evidence.queries.isNotEmpty()) Text(evidence.queries.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall)
            if (evidence.reply.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                StreamingMarkdownText(evidence.reply, isStreaming = false)
            }
        }
    }
}
