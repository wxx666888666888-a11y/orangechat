/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.ScheduledTargetWindow
import me.rerere.rikkahub.data.datastore.ScheduledTaskItem
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class ProactiveMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        const val ACTION_SCHEDULED_TASK = "me.rerere.orangechat.SCHEDULED_TASK"
        const val EXTRA_SCHEDULED_TASK_ID = "scheduled_task_id"
        private const val REQUEST_CODE = 10001
        private const val SCHEDULED_TASK_REQUEST_BASE = 20000

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_SCHEDULED_IDS = "scheduled_task_ids"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            // 始终同步定时任务闹钟（与原版随机主动消息互不干扰，独立生效）
            scheduleAllTasks(context, setting)

            if (!setting.enabled) {
                cancelRandomProactive(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceAtLeast(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            val delayMinutes = Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            setExactAlarmSafely(alarmManager, triggerTime, pendingIntent)

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes")
            ProactiveMessageWorker.scheduleNext(context, setting)
        }

        private fun setExactAlarmSafely(
            alarmManager: AlarmManager,
            triggerTime: Long,
            pendingIntent: PendingIntent
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set exact alarm, falling back to inexact", e)
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            }
        }

        fun scheduleAllTasks(context: Context, setting: ProactiveMessageSetting) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 先清理旧的定时闹钟
            val oldIds = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
            oldIds.forEach { oldId ->
                val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                    action = ACTION_SCHEDULED_TASK
                    putExtra(EXTRA_SCHEDULED_TASK_ID, oldId)
                }
                val reqCode = SCHEDULED_TASK_REQUEST_BASE + (oldId.hashCode() and 0x0FFFFFFF)
                val pi = PendingIntent.getBroadcast(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                pi?.let { runCatching { alarmManager.cancel(it) } }
            }

            val activeIds = mutableSetOf<String>()
            setting.scheduledTasks.filter { it.enabled && it.id.isNotBlank() && it.daysOfWeek.isNotEmpty() }.forEach { task ->
                val nextTime = computeNextTaskTriggerMillis(task)
                if (nextTime != null) {
                    val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                        action = ACTION_SCHEDULED_TASK
                        putExtra(EXTRA_SCHEDULED_TASK_ID, task.id)
                    }
                    val reqCode = SCHEDULED_TASK_REQUEST_BASE + (task.id.hashCode() and 0x0FFFFFFF)
                    val pi = PendingIntent.getBroadcast(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setExactAlarmSafely(alarmManager, nextTime, pi)
                    activeIds.add(task.id)
                    Log.d(TAG, "Scheduled task '${task.title}' (${task.id}) at ${java.util.Date(nextTime)}")
                }
            }

            prefs.edit().putStringSet(KEY_SCHEDULED_IDS, activeIds).apply()
        }

        private fun computeNextTaskTriggerMillis(task: ScheduledTaskItem): Long? {
            if (task.daysOfWeek.isEmpty()) return null
            val now = System.currentTimeMillis()
            for (dayOffset in 0..7) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, task.hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // Calendar: SUNDAY=1, MONDAY=2 ... SATURDAY=7 -> 转为 1=周一..7=周日
                val calDow = cal.get(Calendar.DAY_OF_WEEK)
                val isoDow = if (calDow == Calendar.SUNDAY) 7 else calDow - 1
                if (isoDow in task.daysOfWeek && cal.timeInMillis > now + 2000L) {
                    return cal.timeInMillis
                }
            }
            return null
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        private fun cancelRandomProactive(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled proactive message alarm")
            }
            ProactiveMessageWorker.cancel(context)
        }

        fun cancel(context: Context) {
            cancelRandomProactive(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
            }
            startTriggerServiceSafely(context, serviceIntent)
        }

        fun startTriggerServiceSafely(context: Context, serviceIntent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "startForegroundService blocked by OS (ColorOS/Android 12+), fallback to startService", e)
                runCatching { context.startService(serviceIntent) }
            }
        }
    }

    suspend fun buildProactiveContext(context: Context, settings: Settings): String {
        val sb = StringBuilder()
        sb.appendLine("[主动消息上下文]")

        try {
            val lastMessageTime = getLastMessageTime()
            if (lastMessageTime != null) {
                val nowMs = java.lang.System.currentTimeMillis()
                val lastMs = lastMessageTime.toEpochMilliseconds()
                val diffMs = nowMs - lastMs
                val duration = diffMs.milliseconds
                val minutesAgo = duration.inWholeMinutes
                val hoursAgo = duration.inWholeHours
                when {
                    hoursAgo > 24 -> sb.appendLine("距离上次聊天: ${hoursAgo / 24}天${hoursAgo % 24}小时")
                    hoursAgo > 0 -> sb.appendLine("距离上次聊天: ${hoursAgo}小时${minutesAgo % 60}分钟")
                    else -> sb.appendLine("距离上次聊天: ${minutesAgo}分钟")
                }
            } else {
                sb.appendLine("距离上次聊天: 很久没有聊天了")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
        }

        val currentTime = java.lang.System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("当前时间: ${sdf.format(java.util.Date(currentTime))}")

        try {
            val amapApiKey = settings.systemToolsSetting.amapApiKey
            if (amapApiKey.isNotBlank()) {
                val amapService = AmapService(amapApiKey)
                val locationService = LocationService(context, amapService)
                val locationResult = locationService.getCurrentLocation(amapApiKey)
                if (locationResult.isSuccess) {
                    val location = locationResult.getOrThrow()
                    val locationLine = if (location.address.isNotBlank()) {
                        "当前位置: ${location.address}"
                    } else {
                        "当前坐标: ${location.latitude}, ${location.longitude}"
                    }
                    if (!location.isFresh) {
                        val ageMinutes = location.ageMs / 60000
                        sb.appendLine("$locationLine（注意：这是大约 ${ageMinutes} 分钟前的定位，可能不是当前实时位置，不要当作用户现在就在这里）")
                    } else {
                        sb.appendLine(locationLine)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location context", e)
        }

        try {
            val appUsageService = AppUsageService(context)
            val usageResult = appUsageService.getTodayUsageStats()
            if (usageResult.isSuccess) {
                val usageStats = usageResult.getOrThrow()
                if (usageStats.isNotEmpty()) {
                    sb.appendLine("今日应用使用:")
                    usageStats.take(5).forEach { stat ->
                        val minutes = stat.totalTimeInForeground / 60000
                        if (minutes > 0) {
                            sb.appendLine("  - ${stat.appName}: ${minutes}分钟")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app usage context", e)
        }

        try {
            val appUsageService = AppUsageService(context)
            val foregroundResult = appUsageService.getForegroundApp()
            if (foregroundResult.isSuccess) {
                val foregroundApp = foregroundResult.getOrThrow()
                if (foregroundApp.isNotBlank()) {
                    sb.appendLine("当前前台应用: $foregroundApp")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get foreground app", e)
        }

        try {
            val notifications = me.rerere.rikkahub.data.service.RikkaNotificationListenerService.getTodayNotifications().take(10)
            if (notifications.isNotEmpty()) {
                sb.appendLine("今日通知（最近${notifications.size}条）:")
                notifications.forEach { notif ->
                    sb.appendLine("  - [${notif.appName}] ${notif.title}: ${notif.content.take(50)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get notifications", e)
        }

        try {
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                sb.appendLine("设备电量: ${pct}%${if (isCharging) "（充电中）" else ""}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery info", e)
        }

        sb.appendLine()
        sb.appendLine("请根据以上上下文，以自然、关心、有趣的方式主动给用户发一条消息。")
        sb.appendLine()
        sb.appendLine("重要规则：")
        sb.appendLine("- 绝对不要复述上一轮的对话内容，要发新的话题或新的关心")
        sb.appendLine("- 如果上一轮已经说过类似的话，这次换一个完全不同的角度")
        sb.appendLine("- 不要提及你是在定时发消息，要像自然想起对方一样")
        sb.appendLine("- 绝对不要提及任何数据来源、工具使用、传感器数据、位置服务、应用使用统计等技术细节")
        sb.appendLine("- 不要说\"根据xxx\"、\"我注意到xxx数据\"之类暴露信息来源的话")
        sb.appendLine("- 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说")
        sb.appendLine("- 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容")
        sb.appendLine("- 不要调用任何工具或函数，只输出纯文本回复")
        sb.appendLine("- 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话")
        return sb.toString()
    }

    suspend fun getLastMessageTimeMs(): Long {
        return try { getLastMessageTime()?.toEpochMilliseconds() ?: 0L } catch (e: Exception) { 0L }
    }

    private suspend fun getLastMessageTime(): kotlinx.datetime.Instant? {
        return try {
            val settings = settingsStore.settingsFlow.first()
            val assistantId = settings.assistantId
            val recentConversations = conversationRepository.getRecentConversations(assistantId, limit = 1)
            if (recentConversations.isNotEmpty()) {
                val conv = recentConversations.first()
                val fullConv = conversationRepository.getConversationById(conv.id)
                val localDateTime: LocalDateTime? = fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
                localDateTime?.toInstant(TimeZone.currentSystemDefault())
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
            null
        }
    }
}

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun on
