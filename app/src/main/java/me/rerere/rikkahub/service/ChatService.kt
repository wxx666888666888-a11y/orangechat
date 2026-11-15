package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.*
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
const val COMPRESS_DIVIDER_TAG = "━━━ ✂️ 上下文摘要分割线"
private val JUMP_TOKEN = "[" + "JUMP" + "]"
private val JUMP_REGEX = Regex("\\[" + "JUMP" + "\\]", RegexOption.IGNORE_CASE)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        VoiceMessageTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val pluginToolProvider: PluginToolProvider,
    private val pluginLoader: PluginLoader,
    private val workspaceRepository: WorkspaceRepository,
    private val memoryBankService: MemoryBankService,
    private val folderRepository: FolderRepository,
) {
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    private val observerAdded = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val addObserverRunnable = Runnable {
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            observerAdded.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add lifecycle observer", e)
        }
    }

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addObserverRunnable.run()
        } else {
            mainHandler.post(addObserverRunnable)
        }
    }

    fun cleanup() = runCatching {
        val removeObserverRunnable = Runnable {
            try {
                if (observerAdded.get()) {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
                    observerAdded.set(false)
                } else {
                    mainHandler.removeCallbacks(addObserverRunnable)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove lifecycle observer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeObserverRunnable.run()
        } else {
            mainHandler.post(removeObserverRunnable)
        }
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    internal fun getOrCreateSession(conversationId: Uuid): ConversationSession {
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

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> = getOrCreateSession(conversationId).state
    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> = sessions[conversationId]?.generationJob ?: flowOf(null)
    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> = sessions[conversationId]?.processingStatus ?: MutableStateFlow(null)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) flowOf(emptyMap())
            else combine(currentSessions.map { s -> s.generationJob.map { job -> s.id to job } }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId)
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            val currentSettings = settingsStore.settingsFlow.value
            if (currentSettings.assistantId != conversation.assistantId) {
                settingsStore.updateAssistant(conversation.assistantId)
            }
        } else {
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val settings = settingsStore.settingsFlow.first()

                try {
                    val proactiveSetting = settings.proactiveMessageSetting
                    if (proactiveSetting.enabled || proactiveSetting.scheduledTasks.any { it.enabled }) {
                        me.rerere.rikkahub.data.service.ProactiveMessageService.resetTimer(context, proactiveSetting)
                    }
                } catch (e: Exception) {
                    Log.w("ChatService", "Failed to reset proactive timer", e)
                }

                val (assistant, processedContent) = session.saveMutex.withLock {
                    val latestConversation = session.state.value
                    val assistant = settings.getAssistantById(latestConversation.assistantId) ?: settings.getCurrentAssistant()
                    val processedContent = preprocessUserInputParts(content, assistant)

                    val newConversation = latestConversation.copy(
                        messageNodes = latestConversation.messageNodes + UIMessage(
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
                            "assistant_id" to JsonPrimitive(assistant.id.toString()),
                            "conversation_id" to JsonPrimitive(conversationId.toString()),
                            "message" to JsonPrimitive(processedContent.mapNotNull { if (it is UIMessagePart.Text) it.text else null }.joinToString("\n")),
                            "role" to JsonPrimitive("user"),
                            "timestamp" to JsonPrimitive(System.currentTimeMillis())
                        )
                    )
                    appScope.launch { runCatching { pluginLoader.callEvent("message_sent", eventData) } }
                }

                try {
                    val settingsRaw = settingsStore.settingsFlowRaw.first()
                    val externalMemoryConfigs = settingsRaw.externalMemories.filter {
                        it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                    }
                    if (externalMemoryConfigs.isNotEmpty()) {
                        val messageText = processedContent.mapNotNull { if (it is UIMessagePart.Text) it.text else null }.joinToString("\n")
                        externalMemoryConfigs.forEach { config ->
                            appScope.launch {
                                runCatching {
                                    me.rerere.rikkahub.data.service.ExternalMemoryService(config).saveMessage(
                                        assistantId = assistant.id.toString(),
                                        conversationId = conversationId.toString(),
                                        role = "user",
                                        content = messageText,
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save user message to external memory", e)
                }

                if (answer) {
                    handleMessageComplete(conversationId)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    fun addProactiveMessage(conversationId: Uuid, aiMessage: UIMessage) {
        launchWithConversationReference(conversationId) {
            try {
                appendProactiveAiMessageUnderLock(conversationId, aiMessage)
            } catch (e: Exception) {
                Log.e(TAG, "addProactiveMessage failed, conversationId=$conversationId", e)
            }
        }
    }

    private suspend fun appendProactiveAiMessageUnderLock(conversationId: Uuid, aiMessage: UIMessage) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.let { job -> if (job.isActive) job.join() }
        session.saveMutex.withLock {
            val currentConversation = conversationRepo.getConversationById(conversationId) ?: session.state.value
            val updated = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + aiMessage.toMessageNode(),
                updateAt = Instant.now()
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

                val settings = settingsStore.settingsFlow.first()
                val currentConversation = conversationRepo.getConversationById(conversationId) ?: getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(currentConversation.assistantId) ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return@launch
                val provider = model.findProvider(settings.providers) ?: return@launch
                val providerHandler = providerManager.getProviderByType(provider)

                val historyMessages = sliceAfterLastCompressDivider(currentConversation.currentMessages).let {
                    if (assistant.contextMessageSize > 0) it.takeLast(assistant.contextMessageSize) else it
                }
                val nodeCountBeforeGeneration = currentConversation.messageNodes.size
                val eventDescription = "[系统事件] 你刚刚主动发起的语音通话邀请被用户直接挂断了（拒接，未接听）。请用你自己的语气自然地回应这件事，简短即可，不要提及\"系统事件\"\"工具\"等技术词汇，不要用引号或标注包裹，就当作正常聊天说一句话。"

                val messages = buildList {
                    if (assistant.systemPrompt.isNotEmpty()) {
                        add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(assistant.systemPrompt))))
                    }
                    addAll(historyMessages)
                    add(UIMessage.user(eventDescription))
                }

                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = assistant.temperature ?: 0.8f,
                        topP = assistant.topP,
                        maxTokens = assistant.maxTokens,
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = assistant.customHeaders + model.customHeaders,
                        customBody = assistant.customBodies + model.customBodies,
                    ),
                )

                val replyText = result.choices[0].message?.toText()?.trim().orEmpty()
                if (replyText.isBlank()) return@launch

                val latestConversation = conversationRepo.getConversationById(conversationId) ?: getConversationFlow(conversationId).value
                val newNodesSinceStart = if (latestConversation.messageNodes.size > nodeCountBeforeGeneration) {
                    latestConversation.messageNodes.subList(nodeCountBeforeGeneration, latestConversation.messageNodes.size)
                } else emptyList()
                if (newNodesSinceStart.any { node -> node.messages.any { it.role == MessageRole.USER } }) return@launch

                addProactiveMessage(conversationId, UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(replyText))))

                if (!isForeground.value) {
                    val senderName = if (assistant.useAssistantName && assistant.useAssistantAvatar) assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) } else model.displayName
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "notifyVoiceCallDeclined failed", e)
            }
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(text = part.text.replaceRegexes(assistant = assistant, scope = AssistantAffectScope.USER, visual = false))
                else -> part
            }
        }
    }

    fun regenerateAtMessage(conversationId: Uuid, message: UIMessage, regenerateAssistantMsg: Boolean = true) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                if (message.role == MessageRole.USER) {
                    session.saveMutex.withLock {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessage(message)
                        val indexAt = conversation.messageNodes.indexOf(node)
                        val newConversation = conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1))
                        saveConversation(conversationId, newConversation)
                    }
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val conversation = session.state.value
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        session.saveMutex.withLock { saveConversation(conversationId, session.state.value) }
                    }
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
        session.setJob(job)
    }

    fun handleToolApproval(conversationId: Uuid, toolCallId: String, approved: Boolean, reason: String = "", answer: String? = null) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                val updatedNodes = session.saveMutex.withLock {
                    val conversation = session.state.value
                    val nodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) part.copy(approvalState = newApprovalState) else part
                                    }
                                )
                            }
                        )
                    }
                    saveConversation(conversationId, conversation.copy(messageNodes = nodes))
                    nodes
                }

                val hasPendingTools = updatedNodes.any { node -> node.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending } }
                if (!hasPendingTools) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }
        session.setJob(job)
    }

    private fun sliceAfterLastCompressDivider(messages: List<UIMessage>): List<UIMessage> {
        val idx = messages.indexOfLast { msg ->
            msg.parts.any { it is UIMessagePart.Text && it.text.contains(COMPRESS_DIVIDER_TAG) }
        }
        return if (idx >= 0) messages.subList(idx, messages.size) else messages
    }

    private suspend fun handleMessageComplete(conversationId: Uuid, messageRange: ClosedRange<Int>? = null) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantName) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else model.displayName

        val session = getOrCreateSession(conversationId)

        runCatching {
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val allCurrentMessages = conversation.currentMessages.let {
                if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
            }
            val dividerIdx = allCurrentMessages.indexOfLast { msg ->
                msg.parts.any { it is UIMessagePart.Text && it.text.contains(COMPRESS_DIVIDER_TAG) }
            }
            val preservedPrefix = if (dividerIdx > 0) allCurrentMessages.subList(0, dividerIdx) else emptyList()
            val activeMessages = if (dividerIdx >= 0) allCurrentMessages.subList(dividerIdx, allCurrentMessages.size) else allCurrentMessages

            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = activeMessages,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(assistant.id.toString()),
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) addAll(createSearchTools(settings))
                    addAll(localTools.getTools(assistant.localTools, ToolInvocationContext(callerAssistantId = assistant.id.toString(), callerConversationId = conversationId.toString())))
                    val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions().toMutableSet()
                    if (settings.externalMemories.any { it.enabled }) {
                        systemToolsOptions.add(SystemToolOption.SupabaseQuery)
                    }
                    if (systemToolsOptions.isNotEmpty()) {
                        addAll(SystemTools(context, settings).getTools(systemToolsOptions, conversation.currentMessages, filesManager))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(createSkillTools(enabledSkills = assistant.enabledSkills, allSkills = skillManager.listSkills(), skillManager = skillManager))
                    }
                    mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                        add(
                            Tool(
                                name = ToolNaming.buildMcpToolName(serverId, tool.name),
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = tool.needsApproval,
                                execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                            )
                        )
                    }
                    addAll(pluginToolProvider.getTools())
                },
                pluginPromptInjections = pluginToolProvider.getPluginPromptInjections(),
                conversationId = conversationId.toString(),
            ).onCompletion {
                cancelLiveUpdateNotification(conversationId)
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val merged = if (preservedPrefix.isNotEmpty()) preservedPrefix + chunk.messages else chunk.messages
                        val updatedConversation = getConversationFlow(conversationId).value.updateCurrentMessages(merged)
                        updateConversation(conversationId, updatedConversation)

                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, merged, senderName)
                        }
                    }
                }
            }
        }.onFailure {
            cancelLiveUpdateNotification(conversationId)
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
        }.onSuccess {
            val finalConversation = session.saveMutex.withLock {
                val latest = getConversationFlow(conversationId).value
                saveConversation(conversationId, latest)
                latest
            }

            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                launchNeteaseCloudMusic(lastAssistantMessage?.toText() ?: "")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch NetEase Cloud Music", e)
            }

            try {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val rawText = lastAssistantMessage?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text } ?: ""
                if (rawText.contains(JUMP_TOKEN, ignoreCase = true)) {
                    val cleanedText = rawText.replace(JUMP_REGEX, "").trim()
                    if (lastAssistantMessage != null && cleanedText != rawText) {
                        val cleanedMessage = lastAssistantMessage.copy(
                            parts = lastAssistantMessage.parts.map { part ->
                                if (part is UIMessagePart.Text) UIMessagePart.Text(part.text.replace(JUMP_REGEX, "").trim()) else part
                            }
                        )
                        val cleanedConversation = finalConversation.copy(
                            messageNodes = finalConversation.messageNodes.map { node ->
                                node.copy(messages = node.messages.map { msg -> if (msg.id == cleanedMessage.id) cleanedMessage else msg })
                            }
                        )
                        updateConversation(conversationId, cleanedConversation)
                        saveConversation(conversationId, cleanedConversation)
                    }
                    val jumpIntent = Intent(context, RouteActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("conversationId", conversationId.toString())
                    }
                    context.startActivity(jumpIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle jump token in normal chat", e)
            }

            runCatching {
                val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val eventData = JsonObject(
                    mapOf(
                        "assistant_id" to JsonPrimitive(assistant.id.toString()),
                        "conversation_id" to JsonPrimitive(conversationId.toString()),
                        "message" to JsonPrimitive(lastAssistantMessage?.toText() ?: ""),
                        "role" to JsonPrimitive("assistant"),
                        "timestamp" to JsonPrimitive(System.currentTimeMillis())
                    )
                )
                appScope.launch { runCatching { pluginLoader.callEvent("message_received", eventData) } }
            }

            launchWithConversationReference(conversationId) { generateTitle(conversationId, finalConversation) }
            launchWithConversationReference(conversationId) { generateSuggestion(conversationId, finalConversation) }

            try {
                val externalMemoryConfigs = settings.externalMemories.filter {
                    it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                }
                if (externalMemoryConfigs.isNotEmpty()) {
                    val lastAssistantMessage = finalConversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    val messageText = lastAssistantMessage?.toText() ?: ""
                    if (messageText.isNotBlank()) {
                        coroutineScope {
                            externalMemoryConfigs.forEach { config ->
                                launch {
                                    runCatching {
                                        me.rerere.rikkahub.data.service.ExternalMemoryService(config).saveMessage(
                                            assistantId = assistant.id.toString(),
                                            conversationId = conversationId.toString(),
                                            role = "assistant",
                                            content = messageText,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save assistant message to external memory", e)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        messagesNodes = messagesNodes.mapIndexed { _, node ->
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }
            if (hasPendingTools) {
                val hasResumableTool = node.currentMessage.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
                if (hasResumableTool) return@mapIndexed node
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) return@mapIndexed node
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) node.copy(selectIndex = 0) else node
        }.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(UIMessagePart.Text("""{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}""")),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    suspend fun generateTitle(conversationId: Uuid, conversation: Conversation, force: Boolean = false) {
        val shouldGenerate = force || conversation.title.isBlank()
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages.takeLast(4).joinToString("\n\n") { it.summaryAsText() }
                        )
                    ),
                ),
                params = TextGenerationParams(model = model, reasoningLevel = ReasoningLevel.OFF),
            )

            val session = getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                conversationRepo.getConversationById(conversation.id)?.let {
                    saveConversation(conversationId, it.copy(title = result.choices[0].message?.toText()?.trim() ?: ""))
                }
            }
        }.onFailure {
            addError(error = it, conversationId = conversationId, title = context.getString(R.string.error_title_generate_title), solution = ChatErrorSolution.CheckTitleModelSettings)
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val session = getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                updateConversation(conversationId, session.state.value.copy(chatSuggestions = emptyList()))
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages.takeLast(8).joinToString("\n\n") { it.summaryAsText() }
                        )
                    )
                ),
                params = TextGenerationParams(model = model, reasoningLevel = ReasoningLevel.OFF),
            )
            val suggestions = result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

            session.saveMutex.withLock {
                val latestConversation = conversationRepo.getConversationById(conversationId) ?: session.state.value
                saveConversation(conversationId, latestConversation.copy(chatSuggestions = suggestions.take(10)))
            }
        }
    }

    // ---- 压缩对话历史（支持 KE 本窗口插分割线保留历史 & 原版直接替换） ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)
        val maxMessagesPerChunk = 256
        val allNodes = conversation.messageNodes
        val allMessages = sliceAfterLastCompressDivider(conversation.currentMessages)

        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            return splitMessages(messages.subList(0, mid)) + splitMessages(messages.subList(mid, messages.size))
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) "Additional instructions from user: $additionalPrompt" else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(model = model),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress).map { chunk -> async { compressMessages(chunk) } }.awaitAll()
        }

        val inPlace = settings.displaySetting.compressInPlaceMode
        val newMessageNodes = if (inPlace) {
            val combinedSummary = buildString {
                appendLine("$COMPRESS_DIVIDER_TAG（长按删除此条即可恢复上方全部历史） ━━━")
                appendLine()
                appendLine("【前文剧情与上下文摘要】")
                append(compressedSummaries.joinToString("\n\n"))
            }
            val dividerNode = UIMessage.user(combinedSummary).toMessageNode()
            val insertIndex = (allNodes.size - keepRecentMessages.coerceAtLeast(0)).coerceIn(0, allNodes.size)
            buildList {
                addAll(allNodes.subList(0, insertIndex))
                add(dividerNode)
                addAll(allNodes.subList(insertIndex, allNodes.size))
            }
        } else {
            buildList {
                compressedSummaries.forEach { summary -> add(UIMessage.user(summary).toMessageNode()) }
                addAll(messagesToKeep.map { it.toMessageNode() })
            }
        }

        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )
        saveConversation(conversationId, newConversation)
    }

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        cancelLiveUpdateNotification(conversationId)
        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, notificationId = 1) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int = conversationId.hashCode() + 10000

    private fun sendLiveUpdateNotification(conversationId: Uuid, messages: List<UIMessage>, senderName: String) {
        val lastMessage = messages.lastOrNull() ?: return
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage.parts)
        context.sendNotification(channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID, notificationId = getLiveUpdateNotificationId(conversationId)) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return when {
            lastTool != null && !lastTool.isExecuted -> Triple(
                context.getString(R.string.notification_live_update_chip_tool),
                context.getString(R.string.notification_live_update_tool, ToolNaming.toDisplayName(lastTool.toolName)),
                lastTool.input.take(100)
            )
            lastReasoning != null && lastReasoning.finishedAt == null -> Triple(
                context.getString(R.string.notification_live_update_chip_thinking),
                context.getString(R.string.notification_live_update_thinking),
                lastReasoning.reasoning.takeLast(200)
            )
            lastText != null -> Triple(
                context.getString(R.string.notification_live_update_chip_writing),
                context.getString(R.string.notification_live_update_writing),
                lastText.text.takeLast(200)
            )
            else -> Triple(
                context.getString(R.string.notification_live_update_chip_writing),
                context.getString(R.string.notification_live_update_title),
                ""
            )
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(context, conversationId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values.filter { it.state.value.folderId == folderId }.forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val deletedFiles = oldConversation.files.filter { file -> newConversation.files.none { it == file } }
        if (deletedFiles.isNotEmpty()) filesManager.deleteChatFiles(deletedFiles)
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) conversationRepo.insertConversation(updatedConversation)
        else conversationRepo.updateConversation(updatedConversation)
    }

    fun translateMessage(conversationId: Uuid, message: UIMessage, targetLanguage: Locale) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (messageText.isBlank()) return@launch

                updateTranslationField(conversationId, message.id, context.getString(R.string.translating))
                generationHandler.translateText(settings = settings, sourceText = messageText, targetLanguage = targetLanguage) { translatedText ->
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect {}
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(conversationId: Uuid, messageId: Uuid, translationText: String) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(messages = node.messages.map { msg -> if (msg.id == messageId) msg.copy(translation = translationText) else msg })
            } else node
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) return
        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId) ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) return@map node
            edited = true
            node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts), selectIndex = node.messages.size)
        }
        if (!edited) return
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(conversationId: Uuid, messageId: Uuid): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        if (targetNodeIndex == -1) throw NotFoundException("Message not found")

        val copiedNodes = currentConversation.messageNodes.subList(0, targetNodeIndex + 1).map { node ->
            node.copy(
                id = Uuid.random(),
                messages = node.messages.map { message -> message.copy(parts = message.parts.map { it.copyWithForkedFileUrl() }) }
            )
        }
        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
        )
        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId } ?: throw NotFoundException("Message node not found")
        if (selectIndex !in targetNode.messages.indices) throw BadRequestException("Invalid selectIndex")
        if (targetNode.selectIndex == selectIndex) return

        val updatedNodes = currentConversation.messageNodes.map { node -> if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node }
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)
        if (updatedConversation == null) {
            if (failIfMissing) throw NotFoundException("Message not found")
            return
        }
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(conversation: Conversation, messageId: Uuid): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        if (targetNodeIndex == -1) return null

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) return@mapIndexedNotNull node
            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) return@mapIndexedNotNull null
            node.copy(messages = nextMessages, selectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex))
        }
        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            return filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url
        }
        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(messages = node.messages.map { msg -> if (msg.id == messageId) msg.copy(translation = null) else msg })
            } else node
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) return

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message -> if (message.id == lastMessage.id) updatedMessage else message }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    private fun launchNeteaseCloudMusic(text: String) {
        if (text.isBlank()) return
        val songId = extractNeteaseSongId(text) ?: return
        val uri = "orpheus://song/$songId/?autoplay=1"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.netease.cloudmusic")
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "orpheuswidget://song/$songId".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun extractNeteaseSongId(text: String): String? {
        Regex("orpheus://song/(\\d+)").find(text)?.let { return it.groupValues[1] }
        Regex("music\\.163\\.com/song\\?.*?(?:^|[^0-9])(\\d{4,})").find(text)?.let { return it.groupValues[1] }
        Regex("music\\.163\\.com/song\\?id=(\\d+)").find(text)?.let { return it.groupValues[1] }
        return null
    }
}
