package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.splitIntoBubbleSegments
import me.rerere.rikkahub.utils.urlDecode
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.time.Duration.Companion.milliseconds

val LocalHazeState = compositionLocalOf<HazeState?> { null }

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalDisplaySettings.current
    val resolvedFontFamily = remember(settings.chatFontFamily, settings.customFontPath) {
        when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
            ChatFontFamily.CUSTOM -> {
                val p = settings.customFontPath
                runCatching {
                    if (p.isNotBlank() && File(p).exists()) FontFamily(Font(File(p))) else FontFamily.Default
                }.getOrDefault(FontFamily.Default)
            }
        }
    }
    val baseStyle = LocalTextStyle.current
    val textStyle = remember(baseStyle, settings.fontSizeRatio, settings.chatTextColor, resolvedFontFamily) {
        baseStyle.copy(
            fontSize = baseStyle.fontSize * settings.fontSizeRatio,
            color = settings.chatTextColor?.let { it.toComposeColor() } ?: Color.Unspecified,
            lineHeight = baseStyle.lineHeight * settings.fontSizeRatio,
            fontFamily = resolvedFontFamily
        )
    }
    val baseTypo = MaterialTheme.typography
    val chatTypography = remember(baseTypo, resolvedFontFamily, settings.fontSizeRatio) {
        baseTypo.copy(
            bodyLarge = baseTypo.bodyLarge.copy(fontFamily = resolvedFontFamily, fontSize = baseTypo.bodyLarge.fontSize * settings.fontSizeRatio, lineHeight = baseTypo.bodyLarge.lineHeight * settings.fontSizeRatio),
            bodyMedium = baseTypo.bodyMedium.copy(fontFamily = resolvedFontFamily, fontSize = baseTypo.bodyMedium.fontSize * settings.fontSizeRatio, lineHeight = baseTypo.bodyMedium.lineHeight * settings.fontSizeRatio),
            bodySmall = baseTypo.bodySmall.copy(fontFamily = resolvedFontFamily, fontSize = baseTypo.bodySmall.fontSize * settings.fontSizeRatio, lineHeight = baseTypo.bodySmall.lineHeight * settings.fontSizeRatio),
            titleLarge = baseTypo.titleLarge.copy(fontFamily = resolvedFontFamily),
            titleMedium = baseTypo.titleMedium.copy(fontFamily = resolvedFontFamily),
            titleSmall = baseTypo.titleSmall.copy(fontFamily = resolvedFontFamily),
            labelLarge = baseTypo.labelLarge.copy(fontFamily = resolvedFontFamily),
            labelMedium = baseTypo.labelMedium.copy(fontFamily = resolvedFontFamily),
            labelSmall = baseTypo.labelSmall.copy(fontFamily = resolvedFontFamily),
        )
    }

    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    MaterialTheme(colorScheme = colorScheme, typography = chatTypography, shapes = MaterialTheme.shapes) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!message.parts.isEmptyUIMessage()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ChatMessageAssistantAvatar(message = message, model = model, assistant = assistant, loading = loading, modifier = Modifier.weight(1f))
                    ChatMessageUserAvatar(message = message, avatar = settings.userAvatar, nickname = settings.userNickname, modifier = Modifier.weight(1f))
                }
            }
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = message.parts,
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                )
                message.translation?.let { CollapsibleTranslationText(content = it, onClickCitation = {}) }
            }

            val showActions = if (lastMessage) !loading else !message.parts.isEmptyUIMessage()
            AnimatedVisibility(visible = showActions, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
                Column(modifier = Modifier.animateContentSize()) {
                    ChatMessageActionButtons(
                        message = message,
                        onRegenerate = onRegenerate,
                        node = node,
                        onUpdate = onUpdate,
                        onOpenActionSheet = { showActionsSheet = true },
                        onTranslate = onTranslate,
                        onClearTranslation = onClearTranslation
                    )
                }
            }
            if (assistant?.enableTimeReminder == true) {
                ProvideTextStyle(textStyle) { ChatMessageNerdLine(message = message) }
            }
        }
    }

    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = { showSelectCopySheet = true },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val txt = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }.trim()
                if (txt.isNotBlank()) {
                    val html = buildMarkdownPreviewHtml(context = context, markdown = txt, colorScheme = colorScheme)
                    navController.navigate(Screen.WebView(content = html.base64Encode()))
                }
            },
            onDismissRequest = { showActionsSheet = false }
        )
    }
    if (showSelectCopySheet) {
        ChatMessageCopySheet(message = message, onDismissRequest = { showSelectCopySheet = false })
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val hapticFeedback = LocalHapticFeedback.current
    val ds = LocalDisplaySettings.current
    val bubbleAlpha = 1f - ds.chatBubbleTransparency / 100f
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ cid ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val out = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items = runCatching { JsonInstant.parseToJsonElement(out).jsonObject["items"]?.jsonArray }.getOrNull() ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (cid == id) { context.openUrl(url); return@handler }
                    }
                }
            }
        }
    }

    LaunchedEffect(ds) {
        snapshotFlow { partsState }.debounce(50.milliseconds).collect { p ->
            if (p.isNotEmpty() && loading && ds.enableMessageGenerationHapticEffect) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            }
        }
    }

    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (!(assistant != null && assistant.cleanThinkingProcess)) {
                    if (block.steps.isNotEmpty()) {
                        val onlyReasoning = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                        ChainOfThought(modifier = Modifier.animateContentSize(), steps = block.steps, collapsedAdaptiveWidth = onlyReasoning) { step ->
                            when (step) {
                                is ThinkingStep.ReasoningStep -> key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(reasoning = step.reasoning, model = model, assistant = assistant, collapsedAdaptiveWidth = onlyReasoning)
                                }
                                is ThinkingStep.ToolStep -> key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(tool = step.tool, loading = loading && !step.tool.isExecuted, allParts = parts, onToolApproval = onToolApproval, onToolAnswer = onToolAnswer)
                                }
                            }
                        }
                    }
                }
            }
            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val displayText = remember(part.text) { part.text.replace(Regex("\\[JUMP\\]", RegexOption.IGNORE_CASE), "") }
                        val userColor = ds.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer
                        val aiColor = ds.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh
                        SelectionContainer {
                            Column {
                                if (role == MessageRole.USER) {
                                    val segs = if (assistant?.splitUserBubbleByLine == true) remember(displayText) { displayText.splitIntoBubbleSegments() } else listOf(displayText)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                                        segs.fastForEachIndexed { i, seg ->
                                            key(i) {
                                                BubbleSurface(ds.userBubbleImagePath, ds.bubbleCornerRadius.dp, userColor, ds.bubbleImageOverlayEnabled, bubbleAlpha, onUserMessageClick) {
                                                    MarkdownBlock(content = seg.replaceRegexes(assistant, AssistantAffectScope.USER, true), onClickCitation = handleClickCitation)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val segs = if (assistant?.splitBubbleByLine == true) remember(displayText) { displayText.splitIntoBubbleSegments() } else listOf(displayText)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        segs.fastForEachIndexed { i, seg ->
                                            key(i) {
                                                if (ds.showAssistantBubble || ds.bubbleGlassStyle != 0) {
                                                    BubbleSurface(ds.assistantBubbleImagePath, ds.bubbleCornerRadius.dp, aiColor, ds.bubbleImageOverlayEnabled, bubbleAlpha) {
                                                        MarkdownBlock(content = seg.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, true), onClickCitation = handleClickCitation)
                                                    }
                                                } else {
                                                    MarkdownBlock(content = seg.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, true), onClickCitation = handleClickCitation, modifier = Modifier.animateContentSize())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    data = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", part.url.toUri().toFile())
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                            shape = RoundedCornerShape(8.dp),
                        ) { Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) { Icon(HugeIcons.Video01, null) } }
                    }
                    is UIMessagePart.Audio -> AudioPlayerBubble(url = part.url)
                    is UIMessagePart.VoiceMessage -> VoiceMessageBubble(voiceMessage = part, isUser = role == MessageRole.USER)
                    is UIMessagePart.Image -> {
                        val isImgLoading = part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImgLoading) {
                            Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).shimmer(true))
                        } else {
                            ZoomableAsyncImage(model = part.url, contentDescription = null, modifier = Modifier.clip(MaterialTheme.shapes.medium).height(72.dp))
                        }
                    }
                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    data = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", part.url.toUri().toFile())
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Icon(painterResource(R.drawable.docx), null, Modifier.size(20.dp))
                                        "application/pdf" -> Icon(painterResource(R.drawable.pdf), null, Modifier.size(20.dp))
                                        else -> Icon(HugeIcons.File02, null, Modifier.size(20.dp))
                                    }
                                    Text(text = part.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    if (annotations.isNotEmpty()) {
        Column(modifier = Modifier.animateContentSize()) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f))) {
                    Column(
                        modifier = Modifier.drawWithContent {
                            drawContent()
                            drawRoundRect(color = contentColor.copy(alpha = 0.2f), size = Size(width = 10f, height = size.height))
                        }.padding(start = 16.dp).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            if (annotation is UIMessageAnnotation.UrlCitation) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                    Text(buildAnnotatedString { append("${index + 1}. "); withLink(LinkAnnotation.Url(annotation.url)) { append(annotation.title.urlDecode()) } })
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { expand = !expand }) { Text(stringResource(R.string.citations_count, annotations.size)) }
        }
    }

    if (role == MessageRole.ASSISTANT) {
        EditedFilesList(parts = parts, assistant = assistant)
    }
}

@Composable
private fun BubbleSurface(
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val ds = LocalDisplaySettings.current
    val hazeState = LocalHazeState.current
    val shape = RoundedCornerShape(cornerRadius)
    val hasImage = imagePath.isNotBlank() && File(imagePath).exists()

    val effectiveAlpha = when (ds.bubbleGlassStyle) {
        1 -> (bubbleAlpha * 0.42f).coerceIn(0.10f, 0.72f)
        2 -> (bubbleAlpha * 0.65f).coerceIn(0.22f, 0.85f)
        else -> bubbleAlpha
    }

    val hazeMod = if (hazeState != null && (ds.enableBlurEffect || ds.bubbleGlassStyle != 0)) {
        val style = when (ds.bubbleGlassStyle) {
            1 -> HazeStyle(
                backgroundColor = color.copy(alpha = 0.15f),
                tint = HazeTint(color.copy(alpha = effectiveAlpha)),
                blurRadius = ds.bubbleBlurRadius.dp,
                noiseFactor = 0.02f
            )
            2 -> HazeStyle(
                backgroundColor = color.copy(alpha = 0.28f),
                tint = HazeTint(color.copy(alpha = effectiveAlpha)),
                blurRadius = (ds.bubbleBlurRadius * 1.35f).dp,
                noiseFactor = 0.08f
            )
            else -> HazeDefaults.style(backgroundColor = color.copy(alpha = effectiveAlpha), blurRadius = ds.bubbleBlurRadius.dp)
        }
        Modifier.hazeEffect(state = hazeState, style = style)
    } else Modifier

    val borderMod = when (ds.bubbleGlassStyle) {
        1 -> Modifier.border(
            width = ds.bubbleBorderWidth.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = (ds.bubbleBorderAlpha * 1.3f).coerceIn(0f, 1f)),
                    Color.White.copy(alpha = (ds.bubbleBorderAlpha * 0.2f).coerceIn(0f, 1f)),
                    Color.White.copy(alpha = (ds.bubbleBorderAlpha * 0.8f).coerceIn(0f, 1f))
                )
            ),
            shape = shape
        )
        2 -> Modifier
            .shadow(elevation = (ds.bubbleBlurRadius / 3f).dp, shape = shape, ambientColor = color.copy(alpha = 0.5f), spotColor = color.copy(alpha = 0.5f))
            .border(width = (ds.bubbleBorderWidth * 0.5f).dp, color = Color.White.copy(alpha = (ds.bubbleBorderAlpha * 0.45f).coerceIn(0f, 1f)), shape = shape)
        else -> Modifier
    }

    val glossBrush = when (ds.bubbleGlassStyle) {
        1 -> Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent
            )
        )
        2 -> Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = 0.12f),
                Color.Transparent
            )
        )
        else -> null
    }

    Box(
        modifier = Modifier
            .animateContentSize()
            .then(borderMod)
            .clip(shape)
            .then(hazeMod)
            .background(
                if (hazeState != null && ds.bubbleGlassStyle != 0) color.copy(alpha = (effectiveAlpha * 0.55f).coerceIn(0.08f, 0.75f))
                else color.copy(alpha = effectiveAlpha)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        if (hasImage) {
            AsyncImage(model = imagePath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (overlayEnabled || ds.bubbleGlassStyle != 0) {
                Box(modifier = Modifier.matchParentSize().background(color.copy(alpha = effectiveAlpha)))
            }
        }
        if (glossBrush != null) {
            Box(modifier = Modifier.matchParentSize().background(glossBrush))
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
internal fun AudioPlayerBubble(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    val waveformBars = remember(url) { val rnd = Random(url.hashCode().toLong()); List(40) { 0.15f + rnd.nextFloat() * 0.85f } }
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f

    DisposableEffect(Unit) { onDispose { mediaPlayer?.release(); mediaPlayer = null } }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { if (it.isPlaying) currentMs = it.currentPosition }
            delay(50)
        }
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    Row(
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable {
                if (isPlaying) { mediaPlayer?.pause(); isPlaying = false }
                else if (mediaPlayer == null || !isPrepared) {
                    val mp = MediaPlayer()
                    try {
                        mp.setDataSource(context, android.net.Uri.parse(url))
                        mp.prepare(); durationMs = mp.duration
                        mp.setOnCompletionListener { isPlaying = false; currentMs = 0 }
                        mp.start(); isPlaying = true; isPrepared = true; mediaPlayer = mp
                    } catch (e: Exception) { mp.release() }
                } else { mediaPlayer?.start(); isPlaying = true }
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(28.dp)) {
            val barCount = waveformBars.size
            val barWidth = 2.5f
            val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
            val played = (progress * barCount).toInt()
            waveformBars.forEachIndexed { idx, r ->
                val h = size.height * r.coerceIn(0.15f, 1f)
                drawRoundRect(if (idx < played) activeColor else inactiveColor, Offset(idx * (barWidth + gap), (size.height - h) / 2f), Size(barWidth, h), CornerRadius(1.5f, 1.5f))
            }
        }
        Spacer(Modifier.width(6.dp))
        val sec = if (isPlaying || currentMs > 0) ((durationMs - currentMs) / 1000).coerceAtLeast(0) else durationMs / 1000
        Text(String.format("%d:%02d", sec / 60, sec % 60), style = MaterialTheme.typography.labelMedium, fontSize = 13.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

@Composable
internal fun VoiceMessageBubble(voiceMessage: UIMessagePart.VoiceMessage, isUser: Boolean) {
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val durationSec = (voiceMessage.duration / 1000).coerceAtLeast(1)
    DisposableEffect(voiceMessage.url) { onDispose { mediaPlayer?.release(); mediaPlayer = null } }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        onClick = {
            if (isPlaying) { mediaPlayer?.stop(); mediaPlayer?.reset(); isPlaying = false }
            else {
                try {
                    val mp = MediaPlayer().apply { setDataSource(voiceMessage.url); prepare(); setOnCompletionListener { isPlaying = false }; start() }
                    isPlaying = true; mediaPlayer?.release(); mediaPlayer = mp
                } catch (_: Exception) {}
            }
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle, null, modifier = Modifier.size(24.dp))
                Text("${durationSec}″", style = MaterialTheme.typography.labelMedium)
            }
            if (voiceMessage.transcript.isNotBlank()) {
                Text(voiceMessage.transcript, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
