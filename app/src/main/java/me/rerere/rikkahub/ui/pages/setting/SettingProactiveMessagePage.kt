package me.rerere.rikkahub.ui.pages.setting

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.ScheduledTargetWindow
import me.rerere.rikkahub.data.datastore.ScheduledTaskItem
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.DeviceEventAiTriggerService
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

object ProactivePageEntryHolder {
    var initialSection: Int = 0 // 0 = 独立主动消息页, 1 = 独立定时任务闹钟页
}

private fun splitAssistantAndModel(raw: String): Pair<String, String> {
    val parts = raw.split("#model:", limit = 2)
    return parts[0] to (parts.getOrNull(1) ?: "")
}

private fun combineAssistantAndModel(assistantId: String, modelId: String): String {
    return if (modelId.isBlank()) assistantId else "$assistantId#model:$modelId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val proactiveSetting = settings.proactiveMessageSetting
    val allModels = remember(settings.providers) { settings.providers.flatMap { it.models } }
    val context = LocalContext.current
    val section = remember { ProactivePageEntryHolder.initialSection }

    var nextTriggerTime by remember { mutableStateOf<Long?>(null) }
    var editingTask by remember { mutableStateOf<ScheduledTaskItem?>(null) }
    var showTaskDialog by remember { mutableStateOf(false) }

    LaunchedEffect(proactiveSetting.enabled, proactiveSetting.minIntervalMinutes, proactiveSetting.maxIntervalMinutes) {
        while (true) {
            nextTriggerTime = if (proactiveSetting.enabled) ProactiveMessageService.getNextTriggerTime(context) else null
            delay(2000L)
        }
    }

    fun saveAndSchedule(updated: ProactiveMessageSetting) {
        vm.updateSettings(settings.copy(proactiveMessageSetting = updated))
        ProactiveMessageService.scheduleNext(context, updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (section == 1) "定时任务闹钟" else "主动消息") },
                navigationIcon = { BackButton() },
                actions = {
                    if (section == 1) {
                        FilledTonalButton(
                            onClick = {
                                editingTask = ScheduledTaskItem(id = UUID.randomUUID().toString())
                                showTaskDialog = true
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加闹钟")
                        }
                    }
                },
                colors = CustomColors.topBarColors
            )
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        if (section == 0) {
            OriginalProactiveSection(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                context = context,
                proactiveSetting = proactiveSetting,
                assistants = settings.assistants,
                models = allModels,
                nextTriggerTime = nextTriggerTime,
                onSave = ::saveAndSchedule
            )
        } else {
            ScheduledAlarmSection(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                tasks = proactiveSetting.scheduledTasks,
                assistants = settings.assistants,
                models = allModels,
                onAddClick = {
                    editingTask = ScheduledTaskItem(id = UUID.randomUUID().toString())
                    showTaskDialog = true
                },
                onEditClick = { task ->
                    editingTask = task
                    showTaskDialog = true
                },
                onToggleTask = { task, enabled ->
                    val nextList = proactiveSetting.scheduledTasks.map {
                        if (it.id == task.id) it.copy(enabled = enabled) else it
                    }
                    saveAndSchedule(proactiveSetting.copy(scheduledTasks = nextList))
                },
                onDeleteTask = { task ->
                    val nextList = proactiveSetting.scheduledTasks.filterNot { it.id == task.id }
                    saveAndSchedule(proactiveSetting.copy(scheduledTasks = nextList))
                }
            )
        }
    }

    if (showTaskDialog && editingTask != null) {
        WheelScheduledTaskDialog(
            initialTask = editingTask!!,
            assistants = settings.assistants,
            models = allModels,
            onDismiss = { showTaskDialog = false },
            onConfirm = { saved ->
                val fixedTask = saved.copy(id = saved.id.ifBlank { UUID.randomUUID().toString() })
                val exists = proactiveSetting.scheduledTasks.any { it.id == fixedTask.id }
                val nextList = if (exists) {
                    proactiveSetting.scheduledTasks.map { if (it.id == fixedTask.id) fixedTask else it }
                } else {
                    proactiveSetting.scheduledTasks + fixedTask
                }
                saveAndSchedule(proactiveSetting.copy(scheduledTasks = nextList))
                showTaskDialog = false
                Toast.makeText(
                    context,
                    "已设定 %02d:%02d 闹钟".format(fixedTask.hour, fixedTask.minute),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun OriginalProactiveSection(
    modifier: Modifier,
    context: Context,
    proactiveSetting: ProactiveMessageSetting,
    assistants: List<Assistant>,
    models: List<Model>,
    nextTriggerTime: Long?,
    onSave: (ProactiveMessageSetting) -> Unit
) {
    var minText by remember(proactiveSetting.minIntervalMinutes) {
        mutableStateOf(proactiveSetting.minIntervalMinutes.toString())
    }
    var maxText by remember(proactiveSetting.maxIntervalMinutes) {
        mutableStateOf(proactiveSetting.maxIntervalMinutes.toString())
    }
    val (pureAssistantId, selectedModelId) = remember(proactiveSetting.assistantId) {
        splitAssistantAndModel(proactiveSetting.assistantId)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用随机主动消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("在设定时间区间内随机关心你", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = proactiveSetting.enabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                    if (!am.canScheduleExactAlarms()) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                    data = "package:${context.packageName}".toUri()
                                                }
                                            )
                                        }
                                    }
                                }
                                onSave(proactiveSetting.copy(enabled = enabled))
                            }
                        )
                    }

                    if (proactiveSetting.enabled && nextTriggerTime != null) {
                        val timeStr = remember(nextTriggerTime) {
                            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(nextTriggerTime))
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "下次预计触发：$timeStr",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = {
                                ProactiveMessageService.triggerNow(context, proactiveSetting)
                                Toast.makeText(context, "已立即触发一次主动消息", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("立即触发测试") }

                        OutlinedButton(
                            onClick = {
                                ProactiveMessageService.resetTimer(context, proactiveSetting)
                                Toast.makeText(context, "已重新计时", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("重置倒计时") }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("间隔与助手设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { v ->
                                minText = v.filter(Char::isDigit)
                                minText.toIntOrNull()?.let { m ->
                                    if (m >= 1) onSave(proactiveSetting.copy(minIntervalMinutes = m))
                                }
                            },
                            label = { Text("最小间隔(分)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { v ->
                                maxText = v.filter(Char::isDigit)
                                maxText.toIntOrNull()?.let { m ->
                                    if (m >= 1) onSave(proactiveSetting.copy(maxIntervalMinutes = m))
                                }
                            },
                            label = { Text("最大间隔(分)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val assistantOptions = remember(assistants) { listOf("") + assistants.map { it.id.toString() } }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("指定触发助手", style = MaterialTheme.typography.bodyMedium)
                        Select(
                            options = assistantOptions,
                            selectedOption = pureAssistantId,
                            onOptionSelected = { newAssId ->
                                onSave(proactiveSetting.copy(assistantId = combineAssistantAndModel(newAssId, selectedModelId)))
                            },
                            optionToString = { id ->
                                if (id.isBlank()) "跟随当前助手"
                                else assistants.find { it.id.toString() == id }?.name?.ifBlank { "未命名" } ?: "跟随当前助手"
                            },
                            modifier = Modifier.width(190.dp)
                        )
                    }

                    val modelOptions = remember(models) { listOf("") + models.map { it.id.toString() } }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("使用模型", style = MaterialTheme.typography.bodyMedium)
                        Select(
                            options = modelOptions,
                            selectedOption = selectedModelId,
                            onOptionSelected = { newModelId ->
                                onSave(proactiveSetting.copy(assistantId = combineAssistantAndModel(pureAssistantId, newModelId)))
                            },
                            optionToString = { id ->
                                if (id.isBlank()) "跟随当前使用模型"
                                else models.find { it.id.toString() == id }?.displayName?.ifBlank { "未命名模型" } ?: "跟随当前使用模型"
                            },
                            modifier = Modifier.width(190.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("悬浮球提醒", style = MaterialTheme.typography.bodyMedium)
                            Text("收到主动消息时在屏幕侧边弹出头像气泡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = proactiveSetting.floatingBubbleEnabled,
                            onCheckedChange = { onSave(proactiveSetting.copy(floatingBubbleEnabled = it)) }
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("激进模式（感知亮屏/切应用）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("独立于定时开关，感知手机动向触发思考", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = proactiveSetting.aggressiveModeEnabled,
                            onCheckedChange = { enabled ->
                                val next = proactiveSetting.copy(aggressiveModeEnabled = enabled)
                                onSave(next)
                                runCatching {
                                    val serviceIntent = Intent(context, DeviceEventAiTriggerService::class.java)
                                    if (enabled) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(serviceIntent)
                                        } else {
                                            context.startService(serviceIntent)
                                        }
                                    } else {
                                        context.stopService(serviceIntent)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledAlarmSection(
    modifier: Modifier,
    tasks: List<ScheduledTaskItem>,
    assistants: List<Assistant>,
    models: List<Model>,
    onAddClick: () -> Unit,
    onEditClick: (ScheduledTaskItem) -> Unit,
    onToggleTask: (ScheduledTaskItem, Boolean) -> Unit,
    onDeleteTask: (ScheduledTaskItem) -> Unit
) {
    val dayNames = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (tasks.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 42.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            HugeIcons.Clock01,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("还没有定时闹钟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "支持上下滑动滚轮调时间、选周几、设隐形话术",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onAddClick, shape = RoundedCornerShape(14.dp)) {
                            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新建第一个闹钟")
                        }
                    }
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                val dayText = if (task.daysOfWeek.size == 7) {
                    "每天"
                } else {
                    task.daysOfWeek.sorted().mapNotNull { dayNames[it] }.joinToString(" ")
                }
                val modeText = when (task.targetMode) {
                    ScheduledTargetWindow.FOLLOW_LATEST -> "最新窗口"
                    ScheduledTargetWindow.NEW_WINDOW -> "新建窗口"
                    ScheduledTargetWindow.FIXED_WINDOW -> "固定窗口"
                }
                val (pureAssId, pureModelId) = splitAssistantAndModel(task.assistantId)
                val assistantName = assistants.find { it.id.toString() == pureAssId }?.name?.ifBlank { "当前助手" } ?: "当前助手"
                val modelName = if (pureModelId.isBlank()) "跟随当前模型" else models.find { it.id.toString() == pureModelId }?.displayName ?: "跟随当前模型"

                Card(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditClick(task) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "%02d:%02d".format(task.hour, task.minute),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (task.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = "$dayText · $modeText · $assistantName · $modelName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (task.prompt.isNotBlank()) {
                                Text(
                                    text = "隐形指令：${task.prompt}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onEditClick(task) }) {
                                Icon(HugeIcons.Edit02, contentDescription = "编辑", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onDeleteTask(task) }) {
                                Icon(
                                    HugeIcons.Delete02,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Switch(
                                checked = task.enabled,
                                onCheckedChange = { onToggleTask(task, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelTimePicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {}

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumberWheelColumn(
                    range = 0..23,
                    selected = hour,
                    label = "时",
                    onSelectedChange = onHourChange,
                    modifier = Modifier.width(100.dp)
                )
                Text(
                    text = ":",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                NumberWheelColumn(
                    range = 0..59,
                    selected = minute,
                    label = "分",
                    onSelectedChange = onMinuteChange,
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}

@Composable
private fun NumberWheelColumn(
    range: IntRange,
    selected: Int,
    label: String,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemsList = remember(range) { range.toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(range))
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val targetIdx = centerIndex.coerceIn(0, itemsList.lastIndex)
            onSelectedChange(itemsList[targetIdx])
            listState.animateScrollToItem(targetIdx)
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = 66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(180.dp)
    ) {
        items(itemsList.size) { index ->
            val value = itemsList[index]
            val isSelected = index == centerIndex
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth()
                    .clickable {
                        onSelectedChange(value)
                        scope.launch { listState.animateScrollToItem(index) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "%02d %s".format(value, label),
                    fontSize = if (isSelected) 22.sp else 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (isSelected) 1f else 0.45f)
                )
            }
        }
    }
}

@Composable
private fun WheelScheduledTaskDialog(
    initialTask: ScheduledTaskItem,
    assistants: List<Assistant>,
    models: List<Model>,
    onDismiss: () -> Unit,
    onConfirm: (ScheduledTaskItem) -> Unit
) {
    val initialSplit = remember(initialTask.assistantId) { splitAssistantAndModel(initialTask.assistantId) }
    var title by remember { mutableStateOf(initialTask.title) }
    var hour by remember { mutableIntStateOf(initialTask.hour.coerceIn(0, 23)) }
    var minute by remember { mutableIntStateOf(initialTask.minute.coerceIn(0, 59)) }
    var days by remember { mutableStateOf(initialTask.daysOfWeek.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) }) }
    var targetMode by remember { mutableStateOf(initialTask.targetMode) }
    var fixedConvId by remember { mutableStateOf(initialTask.fixedConversationId) }
    var assistantId by remember { mutableStateOf(initialSplit.first) }
    var modelId by remember { mutableStateOf(initialSplit.second) }
    var prompt by remember { mutableStateOf(initialTask.prompt) }

    val dayLabels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置定时任务闹钟", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "上下滑动滚轮选择时间（%02d:%02d）".format(hour, minute),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                WheelTimePicker(
                    hour = hour,
                    minute = minute,
                    onHourChange = { hour = it },
                    onMinuteChange = { minute = it }
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("闹钟名称（如：早安叫醒、睡前哄睡）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("重复星期", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayLabels.forEach { (d, label) ->
                        val selected = d in days
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    days = if (selected && days.size > 1) days - d else days + d
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text("触发聊天窗口", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        ScheduledTargetWindow.FOLLOW_LATEST to "跟随最新",
                        ScheduledTargetWindow.NEW_WINDOW to "新建窗口",
                        ScheduledTargetWindow.FIXED_WINDOW to "固定窗口"
                    )
                    modes.forEachIndexed { idx, (m, label) ->
                        SegmentedButton(
                            selected = targetMode == m,
                            onClick = { targetMode = m },
                            shape = SegmentedButtonDefaults.itemShape(idx, modes.size)
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }

                if (targetMode == ScheduledTargetWindow.FIXED_WINDOW) {
                    OutlinedTextField(
                        value = fixedConvId,
                        onValueChange = { fixedConvId = it },
                        label = { Text("固定对话窗口 ID（留空则自动用最近窗口）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val assistantOptions = remember(assistants) { listOf("") + assistants.map { it.id.toString() } }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("触发助手", style = MaterialTheme.typography.labelMedium)
                    Select(
                        options = assistantOptions,
                        selectedOption = assistantId,
                        onOptionSelected = { assistantId = it },
                        optionToString = { id ->
                            if (id.isBlank()) "跟随当前助手"
                            else assistants.find { it.id.toString() == id }?.name?.ifBlank { "未命名" } ?: "跟随当前助手"
                        },
                        modifier = Modifier.width(180.dp)
                    )
                }

                val modelOptions = remember(models) { listOf("") + models.map { it.id.toString() } }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("使用模型", style = MaterialTheme.typography.labelMedium)
                    Select(
                        options = modelOptions,
                        selectedOption = modelId,
                        onOptionSelected = { modelId = it },
                        optionToString = { id ->
                            if (id.isBlank()) "跟随当前使用模型"
                            else models.find { it.id.toString() == id }?.displayName?.ifBlank { "未命名模型" } ?: "跟随当前使用模型"
                        },
                        modifier = Modifier.width(180.dp)
                    )
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("隐形触发话术（发给AI但不在聊天框留痕迹）") },
                    placeholder = { Text("例如：现在是晚上10点半，催欣欣赶紧洗漱睡觉") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        initialTask.copy(
                            title = title.ifBlank { "定时主动消息" },
                            hour = hour,
                            minute = minute,
                            daysOfWeek = days,
                            targetMode = targetMode,
                            fixedConversationId = fixedConvId.trim(),
                            assistantId = combineAssistantAndModel(assistantId, modelId),
                            prompt = prompt.trim()
                        )
                    )
                }
            ) { Text("保存闹钟") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
