package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ScheduledTargetWindow
import me.rerere.rikkahub.data.datastore.ScheduledTaskItem
import me.rerere.rikkahub.data.service.DeviceEventAiTriggerService
import me.rerere.rikkahub.data.service.FloatingBubbleService
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ps = settings.proactiveMessageSetting
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    var showAggressiveRiskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTaskItem?>(null) }

    fun saveTasks(tasks: List<ScheduledTaskItem>) {
        val next = ps.copy(scheduledTasks = tasks)
        vm.updateSettings(settings.copy(proactiveMessageSetting = next))
        ProactiveMessageService.scheduleNext(context, next)
    }

    if (showProactiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showProactiveRiskDialog = false
                val next = ps.copy(enabled = true, aggressiveModeEnabled = false)
                vm.updateSettings(settings.copy(proactiveMessageSetting = next))
                DeviceEventAiTriggerService.stop(context)
                ProactiveMessageService.triggerNow(context, next)
            },
            onDismiss = { showProactiveRiskDialog = false }
        )
    }

    if (showAggressiveRiskDialog) {
        RiskConfirmDialog(
            title = stringResource(R.string.risk_proactive_message_title),
            message = stringResource(R.string.risk_proactive_message_message),
            onConfirm = {
                showAggressiveRiskDialog = false
                val next = ps.copy(aggressiveModeEnabled = true, enabled = false)
                vm.updateSettings(settings.copy(proactiveMessageSetting = next))
                ProactiveMessageService.cancel(context)
                runCatching { context.startForegroundService(Intent(context, DeviceEventAiTriggerService::class.java)) }
            },
            onDismiss = { showAggressiveRiskDialog = false }
        )
    }

    editingTask?.let { task ->
        var title by remember(task) { mutableStateOf(task.title) }
        var hourStr by remember(task) { mutableStateOf(task.hour.toString()) }
        var minStr by remember(task) { mutableStateOf(task.minute.toString()) }
        var days by remember(task) { mutableStateOf(task.daysOfWeek) }
        var mode by remember(task) { mutableStateOf(task.targetMode) }
        var prompt by remember(task) { mutableStateOf(task.prompt) }
        val dayLabels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
        val modeOptions = listOf(
            ScheduledTargetWindow.FOLLOW_LATEST to "跟随最新窗",
            ScheduledTargetWindow.NEW_WINDOW to "新建窗口",
            ScheduledTargetWindow.FIXED_WINDOW to "固定当前窗"
        )

        AlertDialog(
            onDismissRequest = { editingTask = null },
            title = { Text("配置定时主动任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("任务名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hourStr,
                            onValueChange = { hourStr = it.filter(Char::isDigit).take(2) },
                            label = { Text("小时(0-23)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minStr,
                            onValueChange = { minStr = it.filter(Char::isDigit).take(2) },
                            label = { Text("分钟(0-59)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("重复周期（全亮即为每天）", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        dayLabels.forEach { (d, l) ->
                            FilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days && days.size > 1) days - d else days + d },
                                label = { Text(l) }
                            )
                        }
                    }
                    Text("发送窗口模式", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modeOptions.forEachIndexed { idx, (m, label) ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { mode = m },
                                shape = SegmentedButtonDefaults.itemShape(idx, modeOptions.size)
                            ) { Text(label) }
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("隐形触发话术（留空则自然主动找你）") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = task.copy(
                        title = title.ifBlank { "定时任务" },
                        hour = (hourStr.toIntOrNull() ?: 8).coerceIn(0, 23),
                        minute = (minStr.toIntOrNull() ?: 0).coerceIn(0, 59),
                        daysOfWeek = days,
                        targetMode = mode,
                        prompt = prompt
                    )
                    val list = ps.scheduledTasks.toMutableList()
                    val i = list.indexOfFirst { it.id == saved.id }
                    if (i >= 0) list[i] = saved else list.add(saved)
                    saveTasks(list)
                    editingTask = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (ps.scheduledTasks.any { it.id == task.id }) {
                        TextButton(onClick = {
                            saveTasks(ps.scheduledTasks.filterNot { it.id == task.id })
                            editingTask = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { editingTask = null }) { Text("取消") }
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主动消息与定时任务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup(title = { Text("定时任务闹钟（支持每天/周几/跟随最新窗口）") }) {
                    ps.scheduledTasks.forEach { t ->
                        val timeStr = String.format("%02d:%02d", t.hour, t.minute)
                        val modeStr = when (t.targetMode) {
                            ScheduledTargetWindow.FOLLOW_LATEST -> "跟随最新窗口"
                            ScheduledTargetWindow.NEW_WINDOW -> "新建窗口"
                            ScheduledTargetWindow.FIXED_WINDOW -> "固定窗口"
                        }
                        val dayStr = if (t.daysOfWeek.size == 7) "每天" else "周" + t.daysOfWeek.sorted().joinToString("")
                        item(
                            headlineContent = { Text("$timeStr · ${t.title}") },
                            supportingContent = { Text("$dayStr | $modeStr${if (t.prompt.isNotBlank()) " | ${t.prompt.take(20)}" else ""}") },
                            onClick = { editingTask = t },
                            trailingContent = {
                                Switch(
                                    checked = t.enabled,
                                    onCheckedChange = { en ->
                                        saveTasks(ps.scheduledTasks.map { if (it.id == t.id) it.copy(enabled = en) else it })
                                    }
                                )
                            }
                        )
                    }
                    item(
                        headlineContent = { Text("➕ 添加新的定时主动消息任务") },
                        supportingContent = { Text("在指定时间点自动触发AI主动发消息（自动隐藏指令不留痕迹）") },
                        onClick = { editingTask = ScheduledTaskItem() }
                    )
                }
            }
            item {
                CardGroup(title = { Text("原版随机间隔主动消息") }) {
                    item(
                        headlineContent = { Text("启用主动消息") },
                        supportingContent = { Text("开启后AI按设定随机间隔循环主动发消息") },
                        trailingContent = {
                            Switch(
                                checked = ps.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) showProactiveRiskDialog = true
                                    else {
                                        val next = ps.copy(enabled = false)
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = next))
                                        ProactiveMessageService.cancel(context)
                                    }
                                }
                            )
                        }
                    )
                    if (ps.enabled) {
                        var nextTime by remember { mutableStateOf(ProactiveMessageService.getNextTriggerTime(context)) }
                        LaunchedEffect(ps) {
                            while (true) {
                                nextTime = ProactiveMessageService.getNextTriggerTime(context)
                                delay(10_000L)
                            }
                        }
                        item(
                            headlineContent = { Text("下次触发时间") },
                            supportingContent = {
                                val now = System.currentTimeMillis()
                                val trig = nextTime
                                if (trig != null && trig > now) {
                                    val rem = trig - now
                                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    Text("🕐 ${sdf.format(Date(trig))}（剩余 ${rem / 60000}分${(rem % 60000) / 1000}秒）")
                                } else Text("等待调度中...")
                            }
                        )
                        val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
                        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
                        item(
                            headlineContent = { Text("悬浮球提醒") },
                            supportingContent = { Text(if (hasOverlay) "主动消息到达时显示悬浮球" else "需授予悬浮窗权限") },
                            trailingContent = {
                                Switch(
                                    checked = ps.floatingBubbleEnabled,
                                    onCheckedChange = { en ->
                                        if (en && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                            return@Switch
                                        }
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = ps.copy(floatingBubbleEnabled = en)))
                                        if (!en) FloatingBubbleService.dismiss(context)
                                    }
                                )
                            }
                        )
                    }
                    item(
                        headlineContent = { Text("最小间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = ps.minIntervalMinutes.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.takeIf { it > 0 }?.let { vm.updateSettings(settings.copy(proactiveMessageSetting = ps.copy(minIntervalMinutes = it))) } },
                                singleLine = true
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("最大间隔 (分钟)") },
                        supportingContent = {
                            OutlinedTextField(
                                value = ps.maxIntervalMinutes.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.takeIf { it >= ps.minIntervalMinutes }?.let { vm.updateSettings(settings.copy(proactiveMessageSetting = ps.copy(maxIntervalMinutes = it))) } },
                                singleLine = true
                            )
                        }
                    )
                }
            }
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("激进模式") },
                        supportingContent = { Text("每次切换应用、亮屏锁屏、回桌面触发AI思考") },
                        trailingContent = {
                            Switch(
                                checked = ps.aggressiveModeEnabled,
                                onCheckedChange = { en ->
                                    if (en) showAggressiveRiskDialog = true
                                    else {
                                        vm.updateSettings(settings.copy(proactiveMessageSetting = ps.copy(aggressiveModeEnabled = false)))
                                        DeviceEventAiTriggerService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }
                            }
