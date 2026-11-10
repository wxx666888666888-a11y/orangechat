package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ScheduledTargetWindow
import me.rerere.rikkahub.data.datastore.ScheduledTaskItem
import me.rerere.rikkahub.data.service.DeviceEventAiTriggerService
import me.rerere.rikkahub.data.service.FloatingBubbleService
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RiskConfirmDialog
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinInject()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ps = settings.proactiveMessageSetting
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showProactiveRiskDialog by remember { mutableStateOf(false) }
    var showAggressiveRiskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTaskItem?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

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
        var title by remember(task.id) { mutableStateOf(task.title) }
        var hour by remember(task.id) { mutableIntStateOf(task.hour) }
        var minute by remember(task.id) { mutableIntStateOf(task.minute) }
        var days by remember(task.id) { mutableStateOf(task.daysOfWeek) }
        var mode by remember(task.id) { mutableStateOf(task.targetMode) }
        var prompt by remember(task.id) { mutableStateOf(task.prompt) }

        val dayLabels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
        val modeOptions = listOf(
            ScheduledTargetWindow.FOLLOW_LATEST to "跟随最新窗",
            ScheduledTargetWindow.NEW_WINDOW to "新建窗口",
            ScheduledTargetWindow.FIXED_WINDOW to "固定当前窗"
        )

        if (showTimePicker) {
            val tpState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
            var useInputMode by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("选择触发时间")
                        TextButton(onClick = { useInputMode = !useInputMode }) {
                            Text(if (useInputMode) "切换拨盘" else "键盘输入")
                        }
                    }
                },
                text = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (useInputMode) TimeInput(state = tpState) else TimePicker(state = tpState)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        hour = tpState.hour
                        minute = tpState.minute
                        showTimePicker = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { editingTask = null },
            title = { Text("定时任务闹钟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = String.format("%02d : %02d", hour, minute),
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text("点击修改时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("备注名称（如：早安、哄睡）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("重复周期", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { days = setOf(1, 2, 3, 4, 5, 6, 7) }) { Text("全选每天") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dayLabels.forEach { (d, l) ->
                            FilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days && days.size > 1) days - d else days + d },
                                label = { Text(l) }
                            )
                        }
                    }

                    Text("触发窗口", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modeOptions.forEachIndexed { idx, (m, label) ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { mode = m },
                                shape = SegmentedButtonDefaults.itemShape(idx, modeOptions.size)
                            ) { Text(label, maxLines = 1) }
                        }
                    }

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("隐形触发提示（不留痕迹，留空则自由发挥）") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val fixedId = task.id.ifBlank { UUID.randomUUID().toString() }
                    val saved = task.copy(
                        id = fixedId,
                        title = title.ifBlank { "定时提醒" },
                        hour = hour.coerceIn(0, 23),
                        minute = minute.coerceIn(0, 59),
                        daysOfWeek = days,
                        targetMode = mode,
                        prompt = prompt,
                        enabled = true
                    )
                    val list = ps.scheduledTasks.toMutableList()
                    val i = list.indexOfFirst { it.id == fixedId }
                    if (i >= 0) list[i] = saved else list.add(saved)
                    saveTasks(list)
                    editingTask = null
                }) { Text("保存闹钟") }
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
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(if (selectedTab == 0) "主动发消息" else "后台定时任务") },
                    navigationIcon = { BackButton() },
                    scrollBehavior = scrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("原版主动发消息") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("定时任务闹钟 (${ps.scheduledTasks.size})") }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedTab == 0) {
                item {
                    CardGroup(title = { Text("随机间隔主动消息（原版逻辑完全保留）") }) {
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
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        )
                        item(
                            headlineContent = { Text("最大间隔 (分钟)") },
                            supportingContent = {
                                OutlinedTextField(
                                    value = ps.maxIntervalMinutes.toString(),
                                    onValueChange = { v -> v.toIntOrNull()?.takeIf { it >= ps.minIntervalMinutes }?.let { vm.updateSettings(settings.copy(proactiveMessageSetting = ps.copy(maxIntervalMinutes = it))) } },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
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
            } else {
                item {
                    CardGroup(title = { Text("独立后台定时闹钟（与主动发消息互不冲突）") }) {
                        item(
                            headlineContent = { Text("➕ 新建定时任务闹钟", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) },
                            supportingContent = { Text("设定固定几点几分、每周几或每天触发，默认跟随你最后聊天的窗口") },
                            onClick = {
                                editingTask = ScheduledTaskItem(id = UUID.randomUUID().toString())
                            }
                        )
                    }
                }
                if (ps.scheduledTasks.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("还没有定时闹钟，点上方新建一个吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    item {
                        CardGroup(title = { Text("已保存的定时任务（点击可修改或删除）") }) {
                            ps.scheduledTasks.forEach { t ->
                                val timeStr = String.format("%02d:%02d", t.hour, t.minute)
                                val modeStr = when (t.targetMode) {
                                    ScheduledTargetWindow.FOLLOW_LATEST -> "跟随最新窗口"
                                    ScheduledTargetWindow.NEW_WINDOW -> "每次新建窗口"
                                    ScheduledTargetWindow.FIXED_WINDOW -> "固定窗口"
                                }
                                val dayNames = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
                                val dayStr = if (t.daysOfWeek.size >= 7) "每天" else "周" + t.daysOfWeek.sorted().mapNotNull { dayNames[it] }.joinToString("、")
                                item(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(timeStr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (t.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                                        }
                                    },
                                    supportingContent = {
                                        Text("$dayStr · $modeStr" + if (t.prompt.isNotBlank()) "\n指令：${t.prompt}" else "")
                                    },
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
                        }
                    }
                }
            }
        }
    }
}
