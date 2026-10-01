package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.TokenPrices
import com.openminis.app.data.model.UsagePriceResolver
import com.openminis.app.data.model.parseTokenPriceInput
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings destination: includes hidden/custom entries because their past usage still costs money. */
@Composable
fun ModelPricingScreen(
    providerRepository: ProviderRepository,
    onEditModel: (instanceId: String, entryId: String) -> Unit,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    var registry by remember { mutableStateOf<Map<String, ModelsDevApi.ProviderEntry>>(emptyMap()) }
    LaunchedEffect(Unit) { registry = withContext(Dispatchers.IO) { ModelsDevApi.registrySnapshot() } }
    val resolver = remember(config, registry) { UsagePriceResolver(config, registry) }
    val unknown = stringResource(R.string.token_price_unknown)
    SettingsScaffold(title = stringResource(R.string.token_price_title), onBack = onBack) {
        config.instances.forEach { instance ->
            SettingsSection(header = instance.label, footer = stringResource(R.string.token_price_list_footer)) {
                val entries = config.modelEntries.filter { it.providerInstanceId == instance.id }
                entries.forEachIndexed { index, entry ->
                    val prices = resolver.prices(entry)
                    SettingsRow(
                        title = entry.model.displayName,
                        subtitle = "${stringResource(R.string.token_price_input)} ${prices.input ?: unknown} · ${stringResource(R.string.token_price_output)} ${prices.output ?: unknown}",
                        showDivider = index < entries.lastIndex,
                        onClick = { onEditModel(instance.id, entry.id) },
                    )
                }
                if (entries.isEmpty()) SettingsCardBlock { Text(stringResource(R.string.token_price_no_models)) }
            }
        }
    }
}

@Composable
internal fun TokenPriceEditor(
    input: String, output: String, cacheRead: String, cacheWrite: String,
    defaults: TokenPrices,
    onInput: (String) -> Unit, onOutput: (String) -> Unit,
    onCacheRead: (String) -> Unit, onCacheWrite: (String) -> Unit,
    onReset: () -> Unit,
) {
    SettingsSection(header = stringResource(R.string.token_price_title), footer = stringResource(R.string.token_price_editor_footer)) {
        SettingsCardBlock {
            val labels = listOf(R.string.token_price_input, R.string.token_price_output, R.string.token_price_cache_read, R.string.token_price_cache_write)
            val values = listOf(input, output, cacheRead, cacheWrite)
            val base = listOf(defaults.input, defaults.output, defaults.cacheRead, defaults.cacheWrite)
            val callbacks = listOf(onInput, onOutput, onCacheRead, onCacheWrite)
            labels.forEachIndexed { index, label ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(label))
                val valid = parseTokenPriceInput(values[index]).isValid
                SectionTextField(
                    value = values[index], onValueChange = callbacks[index], singleLine = true,
                    placeholder = base[index]?.toString() ?: stringResource(R.string.token_price_unknown),
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (!valid) Text(stringResource(R.string.token_price_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsRow(title = stringResource(R.string.token_price_reset), showChevron = false, showDivider = false, onClick = onReset)
    }
}

@Composable
internal fun tokenCostDisplay(cost: com.openminis.app.data.model.TokenCost): String = when {
    cost.isComplete -> com.openminis.app.data.model.formatTokenCostUsd(cost)
    cost.knownUsd.signum() == 0 -> stringResource(R.string.token_price_unknown)
    else -> stringResource(R.string.token_cost_partial, com.openminis.app.data.model.formatTokenCostUsd(cost))
}
