package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingDisplayMessagePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var ds by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current

    fun update(s: DisplaySetting) {
        ds = s
        vm.updateSettings(settings.copy(displaySetting = s))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fontDir = remember { File(context.filesDir, "custom_fonts").apply { mkdirs() } }
    val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val destFile = File(fontDir, "custom_font_${System.currentTimeMillis()}.ttf")
            context.contentResolver.openInputStream(it)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            update(ds.copy(chatFontFamily = ChatFontFamily.CUSTOM, customFontPath = destFile.absolutePath))
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("消息显示") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        trailingContent = { Switch(checked = ds.showUserAvatar, onCheckedChange = { update(ds.copy(showUserAvatar = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        trailingContent = { Switch(checked = ds.showAssistantBubble, onCheckedChange = { update(ds.copy(showAssistantBubble = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        trailingContent = { Switch(checked = ds.showModelIcon, onCheckedChange = { update(ds.copy(showModelIcon = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        trailingContent = { Switch(checked = ds.showModelName, onCheckedChange = { update(ds.copy(showModelName = it)) }) }
                    )
                    val shapeOptions = listOf(0 to "圆形", 1 to "方圆形", 2 to "方形")
                    item(
                        headlineContent = { Text("头像形状") },
                        supportingContent = {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                shapeOptions.forEachIndexed { idx, (mode, label) ->
                                    SegmentedButton(
                                        selected = ds.avatarShapeMode == mode,
                                        onClick = { update(ds.copy(avatarShapeMode = mode)) },
                                        shape = SegmentedButtonDefaults.itemShape(idx, shapeOptions.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("头像大小") },
                        supportingContent = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = ds.avatarSizeDp, onValueChange = { update(ds.copy(avatarSizeDp = it)) }, valueRange = 24f..60f, modifier = Modifier.weight(1f))
                                Text("${ds.avatarSizeDp.roundToInt()}dp")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_date_below_name_desc)) },
                        trailingContent = { Switch(checked = ds.showDateBelowName, onCheckedChange = { update(ds.copy(showDateBelowName = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        trailingContent = { Switch(checked = ds.showTokenUsage, onCheckedChange = { update(ds.copy(showTokenUsage = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        trailingContent = { Switch(checked = ds.showThinkingContent, onCheckedChange = { update(ds.copy(showThinkingContent = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                        trailingContent = { Switch(checked = ds.autoCloseThinking, onCheckedChange = { update(ds.copy(autoCloseThinking = it)) }) }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                        trailingContent = { Switch(checked = ds.enableLatexRendering, onCheckedChange = { update(ds.copy(enableLatexRendering = it)) }) }
                    )
                    val fontOptions = listOf(
                        ChatFontFamily.DEFAULT to stringResource(R.string.setting_display_page_chat_font_family_default),
                        ChatFontFamily.SERIF to stringResource(R.string.setting_display_page_chat_font_family_serif),
                        ChatFontFamily.MONOSPACE to stringResource(R.string.setting_display_page_chat_font_family_monospace),
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            SingleChoiceSegmentedButtonRow(Modifier.padding(top = 4.dp).fillMaxWidth()) {
                                fontOptions.forEachIndexed { idx, (f, label) ->
                                    SegmentedButton(
                                        selected = ds.chatFontFamily == f,
                                        onClick = { update(ds.copy(chatFontFamily = f)) },
                                        shape = SegmentedButtonDefaults.itemShape(idx, fontOptions.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("消息字体大小（支持1%微调）") },
                        supportingContent = {
                            Column {
                                val pct = (ds.fontSizeRatio * 100).roundToInt()
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { update(ds.copy(fontSizeRatio = ((pct - 1).coerceIn(50, 200)) / 100f)) }) { Text("-1%") }
                                    Slider(
                                        value = ds.fontSizeRatio,
                                        onValueChange = { update(ds.copy(fontSizeRatio = (it * 100).roundToInt() / 100f)) },
                                        valueRange = 0.5f..2f,
                                        steps = 0,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { update(ds.copy(fontSizeRatio = ((pct + 1).coerceIn(50, 200)) / 100f)) }) { Text("+1%") }
                                    Text("$pct%")
                                }
                                MarkdownBlock(
                                    content = stringResource(R.string.setting_display_page_font_size_preview),
                                    style = LocalTextStyle.current.copy(
                                        fontSize = LocalTextStyle.current.fontSize * ds.fontSizeRatio,
                                        lineHeight = LocalTextStyle.current.lineHeight * ds.fontSizeRatio
                                    )
                                )
                            }
                        }
                    )
                    item(
                        headlineContent = { Text("思维链字体大小（支持1%微调）") },
                        supportingContent = {
                            val pct = (ds.thinkingFontSizeRatio * 100).roundToInt()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { update(ds.copy(thinkingFontSizeRatio = ((pct - 1).coerceIn(50, 200)) / 100f)) }) { Text("-1%") }
                                Slider(
                                    value = ds.thinkingFontSizeRatio,
                                    onValueChange = { update(ds.copy(thinkingFontSizeRatio = (it * 100).roundToInt() / 100f)) },
                                    valueRange = 0.5f..2.0f,
                                    steps = 0,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { update(ds.copy(thinkingFontSizeRatio = ((pct + 1).coerceIn(50, 200)) / 100f)) }) { Text("+1%") }
                                Text("$pct%")
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("自定义字体") }) {
                    item(
                        headlineContent = { Text("导入自定义字体") },
                        supportingContent = {
                            Text(if (ds.customFontPath.isNotBlank() && File(ds.customFontPath).exists()) "当前: ${File(ds.customFontPath).name}" else "支持 .ttf / .otf")
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (ds.customFontPath.isNotBlank()) {
                                    TextButton(onClick = {
                                        File(ds.customFontPath).delete()
                                        update(ds.copy(customFontPath = "", chatFontFamily = ChatFontFamily.DEFAULT))
                                    }) { Text("清除") }
                                }
                                TextButton(onClick = { fontPickerLauncher.launch(arrayOf("*/*")) }) { Text("选择字体") }
                            }
                        }
                    )
                }
            }
        }
    }
}
