package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class ScheduledTargetWindow {
    FOLLOW_LATEST, // 跟随最新聊天窗口（上次发消息或最后停留的窗口）
    NEW_WINDOW,    // 每次触发时新建一个对话窗口
    FIXED_WINDOW   // 固定在指定的对话窗口中触发
}

@Serializable
data class ScheduledTaskItem(
    val id: String = Uuid.random().toString(),
    val title: String = "定时主动消息",
    val enabled: Boolean = true,
    val hour: Int = 8,
    val minute: Int = 0,
    // 1=周一, 2=周二, ..., 7=周日；全选即为每天
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val targetMode: ScheduledTargetWindow = ScheduledTargetWindow.FOLLOW_LATEST,
    val fixedConversationId: String = "",
    val assistantId: String = "", // 空则跟随当前助手
    // 隐形触发指令（发给AI但不在界面展示用户消息气泡，空则按默认主动关怀触发）
    val prompt: String = "",
)

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120,
    val aggressiveModeEnabled: Boolean = false,
    val aggressiveMinIntervalSeconds: Int = 60,
    val aggressiveDebounceSeconds: Int = 30,
    val floatingBubbleEnabled: Boolean = false,
    val scheduledTasks: List<ScheduledTaskItem> = emptyList(),
)
