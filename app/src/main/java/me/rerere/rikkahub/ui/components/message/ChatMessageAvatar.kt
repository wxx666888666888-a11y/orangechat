package me.rerere.rikkahub.ui.components.message

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toLocalString
import java.io.File

private fun resolveAvatarShape(shapeMode: Int): Shape = when (shapeMode) {
    1 -> RoundedCornerShape(percent = 28)
    2 -> RoundedCornerShape(percent = 10)
    else -> CircleShape
}

@Composable
private fun ShapedAvatar(avatar: Avatar, name: String, sizeDp: Dp, shapeMode: Int, loading: Boolean) {
    val shape = resolveAvatarShape(shapeMode)
    if (avatar is Avatar.Image && shapeMode != 0) {
        AsyncImage(
            model = avatar.url,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp).clip(shape)
        )
    } else {
        UIAvatar(name = name, modifier = Modifier.size(sizeDp), value = avatar, loading = loading)
    }
}

@Composable
private fun AvatarFrameOverlay(framePath: String, offsetX: Float, offsetY: Float, scale: Float, baseSize: Float) {
    if (framePath.isNotBlank() && File(framePath).exists()) {
        val bitmap = remember(framePath) {
            runCatching { BitmapFactory.decodeFile(framePath)?.asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Avatar Frame",
                modifier = Modifier.size((baseSize * scale).dp).offset(x = offsetX.dp, y = offsetY.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val ds = LocalSettings.current.displaySetting
    val sizeVal = ds.avatarSizeDp.coerceIn(24f, 64f)
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && ds.showUserAvatar) {
        Row(
            modifier = modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                )
                if (ds.showDateBelowName) {
                    Text(
                        text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
            Box(contentAlignment = Alignment.Center) {
                ShapedAvatar(avatar, nickname, sizeVal.dp, ds.avatarShapeMode, false)
                AvatarFrameOverlay(ds.userAvatarFramePath, ds.userAvatarFrameOffsetX, ds.userAvatarFrameOffsetY, ds.userAvatarFrameScale, sizeVal)
            }
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val ds = LocalSettings.current.displaySetting
    val sizeVal = ds.avatarSizeDp.coerceIn(24f, 64f)
    val useAvatar = assistant?.useAssistantAvatar == true
    val useName = assistant?.useAssistantName == true

    if (message.role == MessageRole.ASSISTANT && (model != null || useAvatar || useName)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (ds.showModelIcon) {
                if (useAvatar && assistant != null) {
                    Box(contentAlignment = Alignment.Center) {
                        ShapedAvatar(assistant.avatar, assistant.name, sizeVal.dp, ds.avatarShapeMode, loading)
                        AvatarFrameOverlay(ds.aiAvatarFramePath, ds.aiAvatarFrameOffsetX, ds.aiAvatarFrameOffsetY, ds.aiAvatarFrameScale, sizeVal)
                    }
                } else if (model != null) {
                    Box(contentAlignment = Alignment.Center) {
                        AutoAIIcon(name = model.modelId, modifier = Modifier.size(sizeVal.dp), loading = loading)
                        AvatarFrameOverlay(ds.aiAvatarFramePath, ds.aiAvatarFrameOffsetX, ds.aiAvatarFrameOffsetY, ds.aiAvatarFrameScale, sizeVal)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (ds.showModelName) {
                    val title = when {
                        useName && assistant != null -> assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) }
                        model != null -> model.displayName
                        else -> assistant?.name?.ifEmpty { stringResource(R.string.assistant_page_default_assistant) } ?: ""
                    }
                    Text(text = title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1)
                    if (ds.showDateBelowName) {
                        Text(
                            text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
