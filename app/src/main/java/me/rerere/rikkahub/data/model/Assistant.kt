package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val useAssistantName: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val workspaceId: Uuid? = null,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val allowSkipReply: Boolean = false,
    val externalMemoryIds: Set<Uuid> = emptySet(),
    val splitBubbleByLine: Boolean = false,
    val splitUserBubbleByLine: Boolean = false,
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope { USER, ASSISTANT }

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false,
)

fun String.replaceRegexes(assistant: Assistant?, scope: AssistantAffectScope, visual: Boolean = false): String {
    if (assistant == null || assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            runCatching { acc.replace(Regex(regex.findRegex), regex.replaceString) }.getOrDefault(acc)
        } else acc
    }
}

@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt") BEFORE_SYSTEM_PROMPT,
    @SerialName("after_system_prompt") AFTER_SYSTEM_PROMPT,
    @SerialName("top_of_chat") TOP_OF_CHAT,
    @SerialName("bottom_of_chat") BOTTOM_OF_CHAT,
    @SerialName("at_depth") AT_DEPTH,
}

@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int
    abstract val role: MessageRole

    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),
        val useRegex: Boolean = false,
        val caseSensitive: Boolean = false,
        val scanDepth: Int = 4,
        val constantActive: Boolean = false,
    ) : PromptInjection()
}

@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false
    return keywords.any { keyword ->
        if (useRegex) {
            runCatching {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, opts).containsMatchIn(context)
            }.getOrDefault(false)
        } else {
            context.contains(keyword, ignoreCase = !caseSensitive)
        }
    }
}

fun extractContextForMatching(messages: List<UIMessage>, scanDepth: Int): String =
    messages.takeLast(scanDepth).joinToString("\n") { it.toText() }

fun getTriggeredInjections(injections: List<PromptInjection.RegexInjection>, context: String): List<PromptInjection.RegexInjection> =
    injections.filter { it.isTriggered(context) }.sortedByDescending { it.priority }
