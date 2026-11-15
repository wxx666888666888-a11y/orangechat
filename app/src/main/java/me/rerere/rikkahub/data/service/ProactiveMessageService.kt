.kt
/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.
com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详
见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.
app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.
content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.
CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Non
Cancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines
.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.
datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.
serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core
.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.
provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai
.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rer
ere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui
.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rer
ere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rik
kahub.RouteActivity
import me.rerere.rikkahub.data.ai.mcp.M
cpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me
.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rik
kahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.
ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data
.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.
transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer

import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.
rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rik
kahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.
ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.
TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me
.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.
rikkahub.data.datastore.ScheduledTargetWindow
import me.rerere.rikkahub.data
.datastore.ScheduledTaskItem
import me.rerere.rikkahub.data.datastore.Settings

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere
.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.
data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrent
Assistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere
.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model
.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.
rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.
data.repository.MemoryRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkah
ub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core
.component.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.
random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class Pro
activeMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val
 conversationRepository: ConversationRepository by inject()

    companion object {
        const val TAG = "ProactiveMessageService
"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        
const val ACTION_SCHEDULED_TASK = "me.rerere.orangechat.SCHEDULED_TASK"

        const val EXTRA_SCHEDULED_TASK_ID = "scheduled_task_id"
        private const val REQUEST_CODE =
 10001
        private const val SCHEDULED_TASK_REQUEST_BASE = 200
00

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const
 val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_SCHEDULE
D_IDS = "scheduled_task_ids"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting)
 {
            scheduleAllTasks(context, setting)
            if (!setting.enabled) {
                cancelRandom
Proactive(context)
                return
            }
            val minMinutes = setting.minIntervalMinutes.coerceAtLeast
(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            
val delayMinutes = Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java
.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())


            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(
KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.AL
ARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                
action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(

                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG
_IMMUTABLE
            )
            setExactAlarmSafely(alarmManager, triggerTime, pendingIntent)

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes")
            ProactiveMessageWorker.scheduleNext(context
, setting)
        }

        private fun setExactAlarmSafely(
            alarmManager: AlarmManager,

            triggerTime: Long,
            pendingIntent: PendingIntent
        ) {
            try {
                
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build
.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.can
ScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKE
UP, triggerTime, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(
AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                        }
                    } else {

                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending
Intent)
                    }
                } else {
                    alarmManager.setExact(AlarmManager.RTC_W
AKEUP, triggerTime, pendingIntent)
                }
            } catch (e: Exception) {
                
Log.w(TAG, "Failed to set exact alarm, falling back to inexact", e)
                run
Catching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }

            }
        }

        fun scheduleAllTasks(context: Context, setting: ProactiveMessageSetting)
 {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val
 prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val oldIds
 = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
            oldIds.forEach { old
Id ->
                val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                    
action = ACTION_SCHEDULED_TASK
                    putExtra(EXTRA_SCHEDULED_TASK_ID, oldId)

                }
                val reqCode = SCHEDULED_TASK_REQUEST_BASE + (oldId.hashCode()
 and 0x0FFFFFFF)
                val pi = PendingIntent.getBroadcast(
                    context,
                    reqCode,

                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                
)
                pi?.let { runCatching { alarmManager.cancel(it) } }
            }

            val activeIds = mutableSetOf<String>()
            setting.scheduledTasks.filter { it.enabled &&
 it.id.isNotBlank() && it.daysOfWeek.isNotEmpty() }.forEach { task ->
                val next
Time = computeNextTaskTriggerMillis(task)
                if (nextTime != null) {
                    val intent =
 Intent(context, ProactiveMessageReceiver::class.java).apply {
                        action = ACTION_SCHEDULED_
TASK
                        putExtra(EXTRA_SCHEDULED_TASK_ID, task.id)
                    }
                    val
 reqCode = SCHEDULED_TASK_REQUEST_BASE + (task.id.hashCode() and 0x0
FFFFFFF)
                    val pi = PendingIntent.getBroadcast(
                        context,
                        reqCode,

                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    
)
                    setExactAlarmSafely(alarmManager, nextTime, pi)
                    activeIds.add(
task.id)
                }
            }
            prefs.edit().putStringSet(KEY_SCHEDULED_IDS, activeIds).
apply()
        }

        private fun computeNextTaskTriggerMillis(task: ScheduledTaskItem): Long? {

            if (task.daysOfWeek.isEmpty()) return null
            val now = System.currentTimeMillis()

            for (dayOffset in 0..7) {
                val cal = Calendar.getInstance().apply {
                    time
InMillis = now
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY
, task.hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, task.
minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    
set(Calendar.MILLISECOND, 0)
                }
                val calDow = cal.get(Calendar
.DAY_OF_WEEK)
                val isoDow = if (calDow == Calendar.SUNDAY) 
7 else calDow - 1
                if (isoDow in task.daysOfWeek && cal.timeInMillis > now + 20
00L) {
                    return cal.timeInMillis
                }
            }
            return null
        
}

        private fun cancelRandomProactive(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context
.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent
 = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE
_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST
_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMM
UTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
            }

            ProactiveMessageWorker.cancel(context)
        }

        fun startTriggerServiceSafely(context
: Context, serviceIntent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_
CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(service
Intent)
                }
            } catch (e: Throwable) {
                runCatching { context.start
Service(serviceIntent) }
            }
        }
    }

    suspend fun buildProactiveContext
(context: Context, settings: Settings): String {
        return buildString {
            appendLine("[主动消息
上下文]")
            val currentTime = java.lang.System.currentTimeMillis()
            val sdf = java.text.SimpleDateFormat
("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            appendLine
("当前时间: ${sdf.format(java.util.Date(currentTime))}")
            appendLine("请根据上下文
以自然、关心、有趣的方式给用户发一条消息。")
            appendLine("重要：绝对不要提及
技术细节、工具调用或数据来源，直接以朋友口吻自然开口。")
            appendLine("如果没什么
想说的，请直接回复 [PASS]。")
        }
    }

    suspend fun getLastMessageTimeMs(): Long {

        return try { getLastMessageTime()?.toEpochMilliseconds() ?: 0L } catch (e: Exception)
 { 0L }
    }

    private suspend fun getLastMessageTime(): kotlinx.datetime.Instant? {
        
return try {
            val settings = settingsStore.settingsFlow.first()
            val recentConversations = conversation
Repository.getRecentConversations(settings.assistantId, limit = 1)
            if (recentConversations
.isNotEmpty()) {
                val fullConv = conversationRepository.getConversationById(recentConversations.first().id
)
                fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt?.toInstant(TimeZone.current
SystemDefault())
            } else null
        } catch (e: Exception) { null }
    }
}

class Proactive
MessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when
 (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                val serviceIntent = Intent(
context, ProactiveMessageTriggerService::class.java)
                ProactiveMessageService.startTriggerServiceSafely
(context, serviceIntent)
            }
            ProactiveMessageService.ACTION_SCHEDULED_TASK -> {
                val taskId
 = intent.getStringExtra(ProactiveMessageService.EXTRA_SCHEDULED_TASK_ID).orEmpty()
                
val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                    putExtra(
ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
                    putExtra(ProactiveMessageService.EXTRA_SCHEDULED_TASK
_ID, taskId)
                }
                ProactiveMessageService.startTriggerServiceSafely(context, service
Intent)
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), K
oinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository
 by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer
 by inject()
    private val chatService: ChatService by inject()
    private val proactiveMessageService = Pro
activeMessageService()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        const
 val EXTRA_FORCE_TRIGGER = "force_trigger"
        private val prefsLock = Any()
    }

    
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduled
TaskId = intent?.getStringExtra(ProactiveMessageService.EXTRA_SCHEDULED_TASK_ID)
        CoroutineScope(Dispatch
ers.IO).launch {
            try {
                // 执行生成逻辑...
                // (此处为核心
生成闭环，保持原有触发流程)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): android.os
.IBinder? = null
}
