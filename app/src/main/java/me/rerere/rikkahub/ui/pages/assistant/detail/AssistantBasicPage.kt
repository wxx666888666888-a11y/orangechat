package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_basic)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { onUpdate(assistant.copy(avatar = it)) },
                modifier = Modifier.size(80.dp).heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(label = { Text(stringResource(R.string.assistant_page_name)) }, modifier = Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = { onUpdate(assistant.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider()
            FormItem(label = { Text(stringResource(R.string.assistant_page_tags)) }, modifier = Modifier.padding(8.dp)) {
                TagsInput(value = assistant.tags, tags = tags, onValueChange = { ids, list -> vm.updateTags(ids, list) })
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text("显示时间戳") },
                description = { Text("开启后在每条消息前显示时间戳") },
                tail = {
                    Switch(checked = assistant.enableTimeReminder, onCheckedChange = { onUpdate(assistant.copy(enableTimeReminder = it)) })
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text("清理思维链") },
                description = { Text("开启后，将彻底隐藏AI思考过程，不产生空白气泡") },
                tail = {
                    Switch(checked = assistant.cleanThinkingProcess, onCheckedChange = { onUpdate(assistant.copy(cleanThinkingProcess = it)) })
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_split_bubble_by_line_title)) },
                description = { Text(stringResource(R.string.assistant_page_split_bubble_by_line_desc)) },
                tail = {
                    Switch(checked = assistant.splitBubbleByLine, onCheckedChange = { onUpdate(assistant.copy(splitBubbleByLine = it)) })
                }
            )
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_split_user_bubble_by_line_title)) },
                description = { Text(stringResource(R.string.assistant_page_split_user_bubble_by_line_desc)) },
                tail = {
                    Switch(checked = assistant.splitUserBubbleByLine, onCheckedChange = { onUpdate(assistant.copy(splitUserBubbleByLine = it)) })
                }
            )
        }

        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_chat_model)) },
                description = { Text(stringResource(R.string.assistant_page_chat_model_desc)) },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = { onUpdate(assistant.copy(chatModelId = it.id)) },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_temperature)) },
                description = { Text(buildAnnotatedString { append(stringResource(R.string.assistant_page_temperature_warning)) }) },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { onUpdate(assistant.copy(temperature = if (it) 1.0f else null)) }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var input by remember(assistant.id) { mutableStateOf(assistant.temperature.toString()) }
                    val v = input.toFloatOrNull()
                    OutlinedTextField(
                        value = input,
                        onValueChange = { s ->
                            input = s
                            s.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { onUpdate(assistant.copy(temperature = it)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = v == null || v !in 0f..2f,
                        supportingText = { Text("0 - 2") }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_top_p)) },
                description = { Text(buildAnnotatedString { append(stringResource(R.string.assistant_page_top_p_warning)) }) },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { onUpdate(assistant.copy(topP = if (it) 1.0f else null)) }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var input by remember(assistant.id) { mutableStateOf(topP.toString()) }
                    val v = input.toFloatOrNull()
                    OutlinedTextField(
                        value = input,
                        onValueChange = { s ->
                            input = s
                            s.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { onUpdate(assistant.copy(topP = it)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = v == null || v !in 0f..1f,
                        supportingText = { Text("0 - 1") }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_context_message_size)) },
                description = { Text(stringResource(R.string.assistant_page_context_message_desc)) }
            ) {
                Slider(
                    value = assistant.contextMessageSize.toFloat(),
                    onValueChange = { onUpdate(assistant.copy(contextMessageSize = it.roundToInt())) },
                    valueRange = 0f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (assistant.contextMessageSize > 0) stringResource(R.string.assistant_page_context_message_count, assistant.contextMessageSize)
                    else stringResource(R.string.assistant_page_context_message_unlimited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_stream_output)) },
                description = { Text(stringResource(R.string.assistant_page_stream_output_desc)) },
                tail = { Switch(checked = assistant.streamOutput, onCheckedChange = { onUpdate(assistant.copy(streamOutput = it)) }) }
            )
            HorizontalDivider()
            FormItem(modifier = Modifier.padding(8.dp), label = { Text(stringResource(R.string.assistant_page_thinking_budget)) }) {
                ReasoningButton(reasoningLevel = assistant.reasoningLevel, onUpdateReasoningLevel = { onUpdate(assistant.copy(reasoningLevel = it)) })
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_max_tokens)) },
                description = { Text(stringResource(R.string.assistant_page_max_tokens_desc)) }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { t ->
                        val tokens = if (t.isBlank()) null else t.toIntOrNull()?.takeIf { it > 0 }
                        onUpdate(assistant.copy(maxTokens = tokens))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.assistant_page_max_tokens_no_limit)) }
                )
            }
        }

        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            BackgroundPicker(
                modifier = Modifier.padding(8.dp),
                background = assistant.background,
                backgroundOpacity = assistant.backgroundOpacity,
                onUpdate = { onUpdate(assistant.copy(background = it)) }
            )
            if (assistant.background != null) {
                val op = assistant.backgroundOpacity.coerceIn(0f, 1f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_background_opacity)) },
                    description = { Text(stringResource(R.string.assistant_page_background_opacity_desc)) }
                ) {
                    Slider(
                        value = op,
                        onValueChange = { onUpdate(assistant.copy(backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f)) },
                        valueRange = 0f..1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_background_opacity_value, (op * 100).roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
