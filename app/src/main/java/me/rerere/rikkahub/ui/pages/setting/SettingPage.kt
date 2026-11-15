/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    var showExclusiveBeautifySheet by remember { mutableStateOf(false) }

    val display = settings.displaySetting
    val bgImagePath = display.drawerBackgroundPath.ifBlank { display.inputBackgroundPath }

    if (settings.launchCount > 100 && (settings.launchCount - settings.sponsorAlertDismissedAt) >= 50) {
        AlertDialog(
            onDismissRequest = {
                vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
            },
            icon = { Icon(HugeIcons.WavingHand01, null) },
            title = { Text(stringResource(R.string.setting_page_sponsor_alert_title)) },
            text = { Text(stringResource(R.string.setting_page_sponsor_alert_desc)) },
            confirmButton = {
                Button(onClick = {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
                    navController.navigate(Screen.SettingDonate)
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_dismiss))
                }
            },
        )
    }

    if (showExclusiveBeautifySheet) {
        ExclusiveBeautifyBottomSheet(
            display = display,
            onUpdateDisplay = { newDisplay ->
                vm.updateSettings(settings.copy(displaySetting = newDisplay))
            },
            onDismiss = { showExclusiveBeautifySheet = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 如果设置了自定义背景图，设置页底层透出柔化磨砂壁纸，告别生硬死白底
        if (bgImagePath.isNotBlank()) {
            AsyncImage(
                model = bgImagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
                    .alpha(0.32f)
            )
        }

        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(text = stringResource(R.string.settings))
                    },
                    navigationIcon = {
                        BackButton()
                    },
                    scrollBehavior = scrollBehavior,
                    actions = {
                    },
                    colors = if (bgImagePath.isNotBlank()) {
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        )
                    } else {
                        CustomColors.topBarColors
                    }
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (bgImagePath.isNotBlank()) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)
            } else {
                CustomColors.topBarColors.containerColor
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (settings.isNotConfigured()) {
                    item {
                        ProviderConfigWarningCard(navController)
                    }
                }

                item("generalSettings") {
                    var colorMode by rememberColorMode()
                    val selectedColorModeText = when (colorMode) {
                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                    }
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_general_settings)) },
                    ) {
                        item(
                            leadingContent = { Icon(HugeIcons.Sun01, null) },
                            trailingContent = {
                                Select(
                                    options = ColorMode.entries,
                                    selectedOption = colorMode,
                                    onOptionSelected = {
                                        colorMode = it
                                        navController.navigate(Screen.Setting) {
                                            popUpTo(Screen.Setting) {
                                                inclusive = true
                                            }
                                        }
                                    },
                                    optionToString = {
                                        when (it) {
                                            ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                            ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                            ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                        }
                                    },
                                    modifier = Modifier.width(150.dp)
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                            supportingContent = { Text(selectedColorModeText) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingDisplay) },
                            leadingContent = { Icon(HugeIcons.Settings03, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_display_setting_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_display_setting)) },
                        )
                        // 新增：位于显示设置正下方的「专属美化」独立入口
                        item(
                            onClick = { showExclusiveBeautifySheet = true },
                            leadingContent = { Icon(HugeIcons.Sparkles, null) },
                            supportingContent = { Text("高胶囊/去灰清透输入框、顶栏精简、豆沙粉、免确认重发等独立开关") },
                            headlineContent = { Text("专属美化") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingPlugins) },
                            leadingContent = { Icon(HugeIcons.Package, null) },
                            supportingContent = { Text("管理本地插件，导入ZIP插件包") },
                            headlineContent = { Text("插件管理") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingSecurity) },
                            leadingContent = { Icon(HugeIcons.Shield02, null) },
                            supportingContent = { Text("工具调用确认、自动批准、工作流拦截等安全选项") },
                            headlineContent = { Text("安全设置") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.Assistant) },
                            leadingContent = { Icon(HugeIcons.LookTop, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.Extensions) },
                            leadingContent = { Icon(HugeIcons.Package, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                        )
                    }
                }

                item("modelServices") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                    ) {
                        item(
                            onClick = { navController.navigate(Screen.SettingModels) },
                            leadingContent = { Icon(HugeIcons.AiMagic, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingProvider) },
                            leadingContent = { Icon(HugeIcons.Brain02, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingSearch) },
                            leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingSpeech) },
                            leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingMcp) },
                            leadingContent = { Icon(HugeIcons.McpServer, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingWeb) },
                            leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingSystemTools) },
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = { Text("位置、通知、日历、闹钟等系统工具") },
                            headlineContent = { Text("系统工具") },
                        )
                        item(
                            onClick = {
                                ProactivePageEntryHolder.initialSection = 0
                                navController.navigate(Screen.SettingProactiveMessage)
                            },
                            leadingContent = { Icon(HugeIcons.WavingHand01, null) },
                            supportingContent = { Text("AI 在设定间隔内主动发消息，有记忆有上下文") },
                            headlineContent = { Text("主动消息") },
                        )
                        item(
                            onClick = {
                                ProactivePageEntryHolder.initialSection = 1
                                navController.navigate(Screen.SettingProactiveMessage)
                            },
                            leadingContent = { Icon(HugeIcons.Clock01, null) },
                            supportingContent = { Text("定点几时几分、每周几、隐形指令、指定窗口触发") },
                            headlineContent = { Text("定时任务闹钟") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingWeixinBot) },
                            leadingContent = { Icon(HugeIcons.MessageMultiple01, null) },
                            supportingContent = { Text("把微信号变成 AI 入口，扫码登录后用微信收发消息") },
                            headlineContent = { Text("微信 Bot") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.SettingQqBot) },
                            leadingContent = { Icon(HugeIcons.Message01, null) },
                            supportingContent = { Text("填 AppID/Secret，用 QQ 私聊跟 AI 对话") },
                            headlineContent = { Text("QQ Bot") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.Workflows) },
                            leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                            supportingContent = { Text("Tasker 风格自动化：触发器 + 条件 -> 执行动作，由 AI 编写") },
                            headlineContent = { Text("工作流") },
                        )
                        item(
                            onClick = { navController.navigate(Screen.Health) },
                            leadingContent = { Icon(HugeIcons.Pulse01, null) },
                            supportingContent = { Text("Gadgetbridge 健康数据查看") },
                            headlineContent = { Text("健康数据") },
                        )
                    }
        
