package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.*
import me.rerere.rikkahub.data.datastore.migration.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = {
        listOf(PreferenceStoreV1Migration(), PreferenceStoreV2Migration(), PreferenceStoreV3Migration())
    }
)

class SettingsStore(context: Context, scope: AppScope) : KoinComponent {
    companion object {
        val VERSION = intPreferencesKey("data_version")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val DISCLAIMER_ACCEPTED_AT = intPreferencesKey("disclaimer_accepted_at")
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val PROVIDERS = stringPreferencesKey("providers")
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        val S3_CONFIG = stringPreferencesKey("s3_config")
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")
        val LAUNCH_COUNT = intPreferencesKey("launch_count")
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
        val SYSTEM_TOOLS_SETTING = stringPreferencesKey("system_tools_setting")
        val PROACTIVE_MESSAGE_SETTING = stringPreferencesKey("proactive_message_setting")
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val EXTERNAL_MEMORIES = stringPreferencesKey("external_memories")
        val WECHAT_BOT_SETTING = stringPreferencesKey("wechat_bot_setting")
        val QQ_BOT_SETTING = stringPreferencesKey("qq_bot_setting")
        val MINI_APPS = stringPreferencesKey("mini_apps")
        val FORCE_CONFIRM_TOOL_CALLS = booleanPreferencesKey("force_confirm_tool_calls")
        val WORKFLOW_HEADLESS_BLOCK_SENSITIVE = booleanPreferencesKey("workflow_headless_block_sensitive")
        val AUTO_APPROVE_ALL_TOOLS = booleanPreferencesKey("auto_approve_all_tools")
    }

    private val dataStore = context.settingsStore

    @Volatile
    private var lastAssistantsForCacheInvalidation: List<Assistant>? = null

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }.map { preferences ->
            Settings(
                disclaimerAccepted = preferences[DISCLAIMER_ACCEPTED] == true,
                disclaimerAcceptedAt = preferences[DISCLAIMER_ACCEPTED_AT] ?: 0,
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) } ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let { JsonInstant.decodeFromString(it) } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let { JsonInstant.decodeFromString(it) } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) } ?: DEFAULT_SYSTEM_TTS_ID,
                asrProviders = preferences[ASR_PROVIDERS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let { JsonInstant.decodeFromString(it) } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                systemToolsSetting = preferences[SYSTEM_TOOLS_SETTING]?.let { JsonInstant.decodeFromString(it) } ?: SystemToolsSetting(),
                proactiveMessageSetting = preferences[PROACTIVE_MESSAGE_SETTING]?.let { JsonInstant.decodeFromString(it) } ?: ProactiveMessageSetting(),
                wechatBotSetting = preferences[WECHAT_BOT_SETTING]?.let { JsonInstant.decodeFromString(it) } ?: WechatBotSetting(),
                qqBotSetting = preferences[QQ_BOT_SETTING]?.let { JsonInstant.decodeFromString(it) } ?: QqBotSetting(),
                keepAliveEnabled = preferences[KEEP_ALIVE_ENABLED] == true,
                externalMemories = preferences[EXTERNAL_MEMORIES]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                miniApps = preferences[MINI_APPS]?.let { JsonInstant.decodeFromString(it) } ?: emptyList(),
                forceConfirmToolCalls = preferences[FORCE_CONFIRM_TOOL_CALLS] != false,
                workflowHeadlessBlockSensitive = preferences[WORKFLOW_HEADLESS_BLOCK_SENSITIVE] != false,
                autoApproveAllTools = preferences[AUTO_APPROVE_ALL_TOOLS] == true,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { dp ->
                if (providers.none { p -> p.id == dp.id }) providers.add(dp.copyProvider())
            }
            providers = providers.map { p ->
                val dp = DEFAULT_PROVIDERS.find { d -> d.id == p.id }
                if (dp != null) p.copyProvider(builtIn = dp.builtIn, description = dp.description, shortDescription = dp.shortDescription) else p
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { da ->
                if (assistants.none { a -> a.id == da.id }) assistants.add(da.copy())
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { dt ->
                if (ttsProviders.none { t -> t.id == dt.id }) ttsProviders.add(dt.copyProvider())
            }
            it.copy(providers = providers, assistants = assistants, ttsProviders = ttsProviders)
        }
        .map { settings ->
            val validMcpIds = settings.mcpServers.map { it.id }.toSet()
            val validModeIds = settings.modeInjections.map { it.id }.toSet()
            val validLoreIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { p ->
                    when (p) {
                        is ProviderSetting.OpenAI -> p.copy(models = p.models.distinctBy { m -> m.id })
                        is ProviderSetting.Google -> p.copy(models = p.models.distinctBy { m -> m.id })
                        is ProviderSetting.Claude -> p.copy(models = p.models.distinctBy { m -> m.id })
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { a ->
                    a.copy(
                        mcpServers = a.mcpServers.filter { it in validMcpIds }.toSet(),
                        modeInjectionIds = a.modeInjectionIds.filter { it in validModeIds }.toSet(),
                        lorebookIds = a.lorebookIds.filter { it in validLoreIds }.toSet(),
                        quickMessageIds = a.quickMessageIds.filter { it in validQuickIds }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId?.takeIf { id -> asrProviders.any { it.id == id } } ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid -> settings.providers.flatMap { it.models }.any { it.id == uuid } },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
                miniApps = settings.miniApps.distinctBy { it.id },
            )
        }
        .onEach { settings ->
            if (settings.assistants != lastAssistantsForCacheInvalidation) {
                lastAssistantsForCacheInvalidation = settings.assistants
                get<PebbleEngine>().templateCache.invalidateAll()
            }
        }

    val settingsFlow = settingsFlowRaw.distinctUntilChanged().toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if (settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { p ->
            p[DYNAMIC_COLOR] = settings.dynamicColor
            p[THEME_ID] = settings.themeId
            p[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            p[DEVELOPER_MODE] = settings.developerMode
            p[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            p[DISCLAIMER_ACCEPTED] = settings.disclaimerAccepted
            p[DISCLAIMER_ACCEPTED_AT] = settings.disclaimerAcceptedAt
            p[ENABLE_WEB_SEARCH] = settings.enableWebSearch
            p[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            p[SELECT_MODEL] = settings.chatModelId.toString()
            p[TITLE_MODEL] = settings.titleModelId.toString()
            p[TRANSLATE_MODEL] = settings.translateModeId.toString()
            p[SUGGESTION_MODEL] = settings.suggestionModelId.toString()
            p[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            p[TITLE_PROMPT] = settings.titlePrompt
            p[TRANSLATION_PROMPT] = settings.translatePrompt
            p[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            p[SUGGESTION_PROMPT] = settings.suggestionPrompt
            p[OCR_MODEL] = settings.ocrModelId.toString()
            p[OCR_PROMPT] = settings.ocrPrompt
            p[COMPRESS_MODEL] = settings.compressModelId.toString()
            p[COMPRESS_PROMPT] = settings.compressPrompt
            p[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
            p[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            p[SELECT_ASSISTANT] = settings.assistantId.toString()
            p[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
            p[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            p[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            p[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)
            p[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            p[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            p[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            p[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let { p[SELECTED_TTS_PROVIDER] = it.toString() } ?: p.remove(SELECTED_TTS_PROVIDER)
            p[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let { p[SELECTED_ASR_PROVIDER] = it.toString() } ?: p.remove(SELECTED_ASR_PROVIDER)
            p[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            p[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            p[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            p[WEB_SERVER_ENABLED] = settings.webServerEnabled
            p[WEB_SERVER_PORT] = settings.webServerPort
            p[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            p[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            p[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            p[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            p[LAUNCH_COUNT] = settings.launchCount
            p[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            p[SYSTEM_TOOLS_SETTING] = JsonInstant.encodeToString(settings.systemToolsSetting)
            p[PROACTIVE_MESSAGE_SETTING] = JsonInstant.encodeToString(settings.proactiveMessageSetting)
            p[WECHAT_BOT_SETTING] = JsonInstant.encodeToString(settings.wechatBotSetting)
            p[QQ_BOT_SETTING] = JsonInstant.encodeToString(settings.qqBotSetting)
            p[KEEP_ALIVE_ENABLED] = settings.keepAliveEnabled
            p[EXTERNAL_MEMORIES] = JsonInstant.encodeToString(settings.externalMemories)
            p[MINI_APPS] = JsonInstant.encodeToString(settings.miniApps)
            p[FORCE_CONFIRM_TOOL_CALLS] = settings.forceConfirmToolCalls
            p[WORKFLOW_HEADLESS_BLOCK_SENSITIVE] = settings.workflowHeadlessBlockSensitive
            p[AUTO_APPROVE_ALL_TOOLS] = settings.autoApproveAllTools
        }
    }

    suspend fun update(fn: (Settings) -> Settings) = update(fn(settingsFlow.value))

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { it[SELECT_ASSISTANT] = assistantId.toString() }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { s -> s.copy(assistants = s.assistants.map { if (it.id == assistantId) it.copy(chatModelId = modelId) else it }) }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { s -> s.copy(assistants = s.assistants.map { if (it.id == assistantId) it.copy(reasoningLevel = reasoningLevel) else it }) }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { s -> s.copy(assistants = s.assistants.map { if (it.id == assistantId) it.copy(mcpServers = mcpServers) else it }) }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { s ->
            s.copy(assistants = s.assistants.map {
                if (it.id == assistantId) it.copy(modeInjectionIds = modeInjectionIds, lorebookIds = lorebookIds, quickMessageIds = quickMessageIds) else it
            })
        }
    }
}

@Serializable
data class Settings(
    @Transient val init: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val disclaimerAcceptedAt: Int = 0,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val systemToolsSetting: SystemToolsSetting = SystemToolsSetting(),
    val proactiveMessageSetting: ProactiveMessageSetting = ProactiveMessageSetting(),
    val wechatBotSetting: WechatBotSetting = WechatBotSetting(),
    val qqBotSetting: QqBotSetting = QqBotSetting(),
    val keepAliveEnabled: Boolean = false,
    val externalMemories: List<ExternalMemory> = emptyList(),
    val miniApps: List<MiniApp> = emptyList(),
    val forceConfirmToolCalls: Boolean = true,
    val workflowHeadlessBlockSensitive: Boolean = true,
    val autoApproveAllTools: Boolean = false,
) {
    companion object {
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default") DEFAULT,
    @SerialName("serif") SERIF,
    @SerialName("monospace") MONOSPACE,
    @SerialName("custom") CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val chatBubbleTransparency: Float = 0f,
    val thinkingChainTransparency: Float = 0f,
    val customFontPath: String = "",
    val inputBackgroundPath: String = "",
    val userAvatarFramePath: String = "",
    val aiAvatarFramePath: String = "",
    val userAvatarFrameOffsetX: Float = 0f,
    val userAvatarFrameOffsetY: Float = 0f,
    val userAvatarFrameScale: Float = 1f,
    val aiAvatarFrameOffsetX: Float = 0f,
    val aiAvatarFrameOffsetY: Float = 0f,
    val aiAvatarFrameScale: Float = 1f,
    val drawerBackgroundPath: String = "",
    val drawerItemAlpha: Float = 1f,
    val chatTextColor: Long? = null,
    val globalTextColor: Long? = null,
    val userBubbleColor: Long? = null,
    val assistantBubbleColor: Long? = null,
    val thinkingFontSizeRatio: Float = 1.0f,
    val thinkingBubbleColor: Long? = null,
    val chatBackgroundColor: Long? = null,
    val primaryColor: Long? = null,
    val inputFieldColor: Long? = null,
    val userBubbleImagePath: String = "",
    val assistantBubbleImagePath: String = "",
    val bubbleCornerRadius: Float = 16f,
    val bubbleImageOverlayEnabled: Boolean = false,
    // KE / 北极星多形态毛玻璃 & 头像形状大小 & 本窗口无损压缩开关
    val bubbleGlassStyle: Int = 0,           // 0=橘瓣原版, 1=KE清透毛玻璃, 2=北极星柔光磨砂
    val bubbleBlurRadius: Float = 16f,       // 气泡毛玻璃模糊半径 (dp)
    val bubbleBorderWidth: Float = 0.8f,     // 气泡高光边框宽度 (dp)
    val bubbleBorderAlpha: Float = 0.35f,    // 气泡高光边框透明度
    val avatarShapeMode: Int = 0,            // 0=圆形, 1=方圆形, 2=方形
    val avatarSizeDp: Float = 36f,           // 头像大小 (dp)
    val compressInPlaceMode: Boolean = true, // true=本窗口插入摘要分割线(保留前文可撤回), false=原版替换
    // 新增：三改专属清透美化独立开关组
    val compactTopBarIcons: Boolean = true,          // 1. 顶部栏右侧4图标适度缩小(完整显露左侧模型名)
    val glassTallCapsuleInput: Boolean = false,      // 2. 极简毛玻璃高胶囊输入框(打字回车往下拉高+纸飞机发送)
    val cleanMergeCustomInputBg: Boolean = true,     // 3. 原版双层输入框去灰清透融合+去掉三个小圆点+纸飞机发送
    val dynamicToAssistantHint: Boolean = true,      // 4. 输入框提示词动态改为 "to 助手名"
    val floatingAiTextMode: Boolean = false,         // 5. AI气泡隐形悬浮模式
    val wallpaperBlurRadius: Float = 0f,             // 6. 聊天自定义壁纸磨砂模糊半径 (0~20dp)
    val softDustyPinkDanger: Boolean = true,         // 7. 删除键与停止生成键刺眼红替换为柔和低饱和豆沙淡粉
    val skipRegenerateConfirm: Boolean = true,       // 8. 重新生成免二次确认弹窗
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(BackupItem.DATABASE, BackupItem.FILES),
) {
    @Serializable
    enum class BackupItem { DATABASE, FILES }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }
fun Settings.findModelById(uuid: Uuid): Model? = this.providers.findModelById(uuid)

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    forEach { s -> s.models.forEach { m -> if (m.id == uuid) return m } }
    return null
}

fun Settings.getCurrentChatModel(): Model? = findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
fun Settings.getCurrentAssistant(): Assistant = this.assistants.find { it.id == assistantId } ?: this.assistants.first()
fun Settings.getAssistantById(id: Uuid): Assistant? = this.assistants.find { it.id == id }
fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) = quickMessages.filter { it.id in assistant.quickMessageIds }
fun Settings.getSelectedTTSProvider(): TTSProviderSetting? = selectedTTSProviderId?.let { id -> ttsProviders.find { it.id == id } } ?: ttsProviders.firstOrNull()
fun Settings.getSelectedASRProvider(): ASRProviderSetting? = selectedASRProviderId?.let { id -> asrProviders.find { it.id == id } } ?: asrProviders.firstOrNull()

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val overwrite = this.providerOverwrite
    return if (checkOverwrite && overwrite != null) overwrite.copyProvider(models = emptyList()) else provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { s -> s.models.forEach { m -> if (m.id == this.id) return s } }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(id = DEFAULT_ASSISTANT_ID, name = "", systemPrompt = ""),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(id = DEFAULT_SYSTEM_TTS_ID, name = ""),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
