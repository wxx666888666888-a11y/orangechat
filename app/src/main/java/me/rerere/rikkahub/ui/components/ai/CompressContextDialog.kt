package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.context.LocalProviders
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job
) {
    val settings = LocalSettings.current
    val providers = LocalProviders.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    var additionalPrompt by remember { mutableStateOf("") }
    var inPlaceMode by remember(settings.displaySetting.compressInPlaceMode) {
        mutableStateOf(settings.displaySetting.compressInPlaceMode)
    }
    var tokenInput by remember { mutableStateOf("2000") }
    var keepInput by remember { mutableStateOf("2") }
    val tokenOptions = listOf(500, 1000, 2000, 4000)
    val keepOptions = listOf(0, 1, 2, 3, 6)
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) onDismiss()
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_page_compress_context_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text("压缩模型（报错502可当场切换）", style = MaterialTheme.typography.labelMedium)
                    ModelSelector(
                        modelId = settings.compressModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = { m -> scope.launch { settingsStore.update { it.copy(compressModelId = m.id) } } }
                    )

                    Text("压缩方式", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = inPlaceMode,
                            onClick = {
                                inPlaceMode = true
                                scope.launch {
                                    settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(compressInPlaceMode = true)) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("插分割线(留前文)") }
                        SegmentedButton(
                            selected = !inPlaceMode,
                            onClick = {
                                inPlaceMode = false
                                scope.launch {
                                    settingsStore.update { it.copy(displaySetting = it.displaySetting.copy(compressInPlaceMode = false)) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("原版替换") }
                    }

                    Text("目标摘要 Token（可点选或手填）", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        tokenOptions.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = tokenInput == t.toString(),
                                onClick = { tokenInput = t.toString() },
                                shape = SegmentedButtonDefaults.itemShape(i, tokenOptions.size)
                            ) { Text("$t") }
                        }
                    }
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it.filter(Char::isDigit) },
                        label = { Text("自定义 Token 数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("保留最近几条消息（可点选或手填）", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        keepOptions.forEachIndexed { i, c ->
                            SegmentedButton(
                                selected = keepInput == c.toString(),
                                onClick = { keepInput = c.toString() },
                                shape = SegmentedButtonDefaults.itemShape(i, keepOptions.size)
                            ) { Text("$c") }
                        }
                    }
                    OutlinedTextField(
                        value = keepInput,
                        onValueChange = { keepInput = it.filter(Char::isDigit) },
                        label = { Text("自定义保留消息条数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = { currentJob?.cancel(); currentJob = null }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    val tokens = tokenInput.toIntOrNull()?.coerceAtLeast(100) ?: 2000
                    val keep = keepInput.toIntOrNull()?.coerceAtLeast(0) ?: 2
                    currentJob = onConfirm(additionalPrompt, tokens, keep)
                }) { Text(stringResource(R.string.confirm)) }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
