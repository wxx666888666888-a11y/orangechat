package me.rerere.rikkahub.service


import android.app.Application
import android.app.PendingIntent
import android.content.Context
import
 android.content.Intent
import android.os.Handler
import android.os.Looper
import android.
util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.
coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.
awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines
.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx
.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai
.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rer
ere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rer
ere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me
.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.*
import
 me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope

import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.
rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere
.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rer
ere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data
.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.M
cpManager
import me.rerere.rikkahub.data.ai.tools.*
import me.rer
ere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data
.datastore.*
import me.rerere.rikkahub.data.files.FilesManager
import me
.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub
.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere
.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.
repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me
.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkah
ub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.provider.Plugin
ToolProvider
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere
.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification

import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkah
ub.web.NotFoundException
import me.rerere.workspace.WorkspaceShellStatus
import java.time.
Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.
Uuid

private const val TAG = "ChatService"
const val COMPRESS_DIVIDER_TAG = "━━
━ ✂️ 上下文摘要分割线"
private val JUMP_TOKEN = "[" + "JUMP" + "]"

private val JUMP_REGEX = Regex("\\[" + "JUMP" + "\\]", RegexOption.IGNORE
_CASE)

data class ChatError(
    val id: Uuid = Uuid.random(),
    
val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid?
 = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution?
 = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val
 inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        Document
AsPromptTransformer,
        OcrTransformer,
        VoiceMessageTransformer,
    )
}

private val output
Transformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        Regex
OutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private
 val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo:
 ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,

    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val local
Tools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: Files
Manager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,

    private val pluginLoader: PluginLoader,
    private val workspaceRepository: WorkspaceRepository,
    private val
 memoryBankService: MemoryBankService,
    private val folderRepository: FolderRepository,
) {
    private
 val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    
private val _sessionsVersion = MutableStateFlow(0L)

    private val _errors = MutableStateFlow<
List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()


    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,

        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {

        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title
 = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError
(id: Uuid) {
        _errors.update { list -> list.filter { it.id !=
 id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()

    }

    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow
: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    private val _isForeground =
 MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    
private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START ->
 _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false

            else -> {}
        }
    }

    private val observerAdded = AtomicBoolean(false)
    private val main
Handler = Handler(Looper.getMainLooper())

    private val addObserverRunnable = Runnable {
        try
 {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            observerAdded.set(
true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add
 lifecycle observer", e)
        }
    }

    init {
        if (Looper.myLooper() == Looper
.getMainLooper()) {
            addObserverRunnable.run()
        } else {
            mainHandler.
post(addObserverRunnable)
        }
    }

    fun cleanup() = runCatching {
        
val removeObserverRunnable = Runnable {
            try {
                if (observerAdded.get()) {
                    Process
LifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
                    observerAdded.set(false)
                
} else {
                    mainHandler.removeCallbacks(addObserverRunnable)
                }
            } catch (e: Exception) {

                Log.e(TAG, "Failed to remove lifecycle observer", e)
            }
        }
        
if (Looper.myLooper() == Looper.getMainLooper()) {
            removeObserverRunnable.
run()
        } else {
            mainHandler.post(removeObserverRunnable)
        }
        sessions
.values.forEach { it.cleanup() }
        sessions.clear()
    }

    internal fun get
OrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->

            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                
initial = Conversation.ofId(id = id, assistantId = settings.getCurrentAssistant().id),
                scope = appScope,

                onIdle = { removeSession(it) }
            ).also { _sessionsVersion.value++ }
        }

    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return

        if (session.isInUse) return
        if (sessions.remove(conversationId, session)) {

            session.cleanup()
            _sessionsVersion.value++
        }
    }

    fun addConversationReference(conversationId:
 Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId
: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversation
Reference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch
 {
        addConversationReference(conversationId)
        try {
            block()
        } finally {

            removeConversationReference(conversationId)
        }
    }

    fun getConversationFlow(conversationId:
 Uuid): StateFlow<Conversation> = getOrCreateSession(conversationId).state
    fun getGenerationJobStateFlow(conversationId:
 Uuid): Flow<Job?> = sessions[conversationId]?.generationJob ?: flowOf(null)
    fun getProcessingStatusFlow(
conversationId: Uuid): StateFlow<String?> = sessions[conversationId]?.processingStatus ?: MutableStateFlow(
null)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest
 {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) flowOf(emptyMap())

            else combine(currentSessions.map { s -> s.generationJob.map { job -> s.id to
 job } }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }


    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId)

        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation
(conversationId, conversation)
            val currentSettings = settingsStore.settingsFlow.value
            if (current
Settings.assistantId != conversation.assistantId) {
                settingsStore.updateAssistant(conversation.assistantId)

            }
        } else {
            val currentSettings = settingsStore.settingsFlowRaw.first()

            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id
 = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.
presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    fun sendMessage
(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if
 (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        session.
getJob()?.cancel()

        val job = appScope.launch {
            try {
                val settings = settingsStore.
settingsFlow.first()

                try {
                    val proactiveSetting = settings.proactiveMessageSetting
                    if
 (proactiveSetting.enabled || proactiveSetting.scheduledTasks.any { it.enabled }) {
                        me.rerere
.rikkahub.data.service.ProactiveMessageService.resetTimer(context, proactiveSetting)
                    
}
                } catch (e: Exception) {
                    Log.w("ChatService", "Failed to reset proactive timer",
 e)
                }

                val (assistant, processedContent) = session.saveMutex.withLock {
                    val latestConversation =
 session.state.value
                    val assistant = settings.getAssistantById(latestConversation.assistantId) ?: settings.getCurrentAssistant()

                    val processedContent = preprocessUserInputParts(content, assistant)

                    val newConversation = latestConversation.copy(
                        message
Nodes = latestConversation.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,

                        ).toMessageNode(),
                    )
                    saveConversation(conversationId, newConversation)
                    assistant to processedContent
                }


                runCatching {
                    val eventData = JsonObject(
                        mapOf(
                            "assistant
_id" to JsonPrimitive(assistant.id.toString()),
                            "conversation_id" to JsonPrimitive(conversationId.toString()),

                            "message" to JsonPrimitive(processedContent.mapNotNull { if (it is UIMessagePart.
Text) it.text else null }.joinToString("\n")),
                            "role" to JsonPrimitive("user"),
                            "timestamp
" to JsonPrimitive(System.currentTimeMillis())
                        )
                    )
                    appScope.launch { run
Catching { pluginLoader.callEvent("message_sent", eventData) } }
                }

                try {
                    val
 settingsRaw = settingsStore.settingsFlowRaw.first()
                    val externalMemoryConfigs = settingsRaw.externalMemories.filter {

                        it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                    }

                    if (externalMemoryConfigs.isNotEmpty()) {
                        val messageText = processedContent.mapNotNull { if (
it is UIMessagePart.Text) it.text else null }.joinToString("\n")
                        externalMemoryConfigs.forEach { config
 ->
                            appScope.launch {
                                runCatching {
                                    me.rerere.rikkahub.data.
service.ExternalMemoryService(config).saveMessage(
                                        assistantId = assistant.id.toString(),

                                        conversationId = conversationId.toString(),
                                        role = "user",
                                        content = message
Text,
                                    )
                                }
                            }
                        }
                    }
                } catch (e
: Exception) {
                    Log.w(TAG, "Failed to save user message to external memory", e)

                }

                if (answer) {
                    handleMessageComplete(conversationId)
                }
                
_generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "
sendMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context
.getString(R.string.error_title_send_message))
            }
        }
        session
.setJob(job)
    }

    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage
) {
        launchWithConversationReference(conversationId) {
            try {
                appendProactiveAiMessage
UnderLock(conversationId, aiMessage)
            } catch (e: Exception) {
                Log.e
(TAG, "addProactiveMessage failed, conversationId=$conversationId", e)
            }
        }
    }

    
private suspend fun appendProactiveAiMessageUnderLock(conversationId: Uuid, aiMessage: UIMessage)
 {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.let { job
 -> if (job.isActive) job.join() }
        session.saveMutex.withLock {
            val currentConversation =
 conversationRepo.getConversationById(conversationId) ?: session.state.value
            val updated = currentConversation.copy(
                
messageNodes = currentConversation.messageNodes + aiMessage.toMessageNode(),
                updateAt = Instant.now
()
            )
            updateConversation(conversationId, updated)
            saveConversation(conversationId, updated)

        }
    }

    fun notifyVoiceCallDeclined(conversationId: Uuid) {
        
appScope.launch(Dispatchers.IO) {
            try {
                val session = getOrCreateSession(conversationId)
                
session.getJob()?.let { job -> if (job.isActive) job.join() }

                val
 settings = settingsStore.settingsFlow.first()
                val currentConversation = conversationRepo.getConversationById(conversation
Id) ?: getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(currentConversation.assistantId)
 ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chat
ModelId) ?: return@launch
                val provider = model.findProvider(settings.providers) ?: return@launch

                val providerHandler = providerManager.getProviderByType(provider)

                val historyMessages = sliceAfterLastCompressDivider(current
Conversation.currentMessages).let {
                    if (assistant.contextMessageSize > 0) it.takeLast(assistant.contextMessage
Size) else it
                }
                val nodeCountBeforeGeneration = currentConversation.messageNodes.size
                val eventDescription =
 "[系统事件] 你刚刚主动发起的语音通话邀请被用户直接挂断了（拒接，未接听
）。请用你自己的语气自然地回应这件事，简短即可，不要提及\"系统事件\"\"工具\"等技术
词汇，不要用引号或标注包裹，就当作正常聊天说一句话。"

                val messages = buildList {
                    
if (assistant.systemPrompt.isNotEmpty()) {
                        add(UIMessage(role = MessageRole.SYSTEM
, parts = listOf(UIMessagePart.Text(assistant.systemPrompt))))
                    }
                    addAll(historyMessages)
                    
add(UIMessage.user(eventDescription))
                }

                val result = providerHandler.generateText
(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,

                        temperature = assistant.temperature ?: 0.8f,
                        topP = assistant.topP,
                        maxTokens
 = assistant.maxTokens,
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = assistant.customHeaders + model
.customHeaders,
                        customBodies = assistant.customBodies + model.customBodies,
                    ),
                
)

                val replyText = result.choices[0].message?.toText()?.trim().orEmpty()
                if (
replyText.isBlank()) return@launch

                val latestConversation = conversationRepo.getConversationById(conversationId)
 ?: getConversationFlow(conversationId).value
                val newNodesSinceStart = if (latestConversation.messageNodes.size > node
CountBeforeGeneration) {
                    latestConversation.messageNodes.subList(nodeCountBeforeGeneration, latestConversation.messageNodes.size)

                } else emptyList()
                if (newNodesSinceStart.any { node -> node.messages.any { it
.role == MessageRole.USER } }) return@launch

                addProactiveMessage(conversationId, UIMessage(role = Message
Role.ASSISTANT, parts = listOf(UIMessagePart.Text(replyText))))

                if (!
isForeground.value) {
                    val senderName = if (assistant.useAssistantName && assistant.useAssistantAvatar) assistant.name
.ifEmpty { context.getString(R.string.assistant_page_default_assistant) } else model.displayName
                    send
GenerationDoneNotification(conversationId, senderName)
                }
            } catch (e: Exception) {
                Log
.e(TAG, "notifyVoiceCallDeclined failed", e)
            }
        }
    }


    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<U
IMessagePart> {
        return parts.map { part ->
            when (part) {
                is
 UIMessagePart.Text 
