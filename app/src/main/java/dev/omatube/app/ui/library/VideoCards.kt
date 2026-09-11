package dev.omatube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.omatube.app.model.Video
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors

@Composable
fun OmaThumbnail(
    video: Video,
    automation: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOmaColors.current
    Box(
        modifier = modifier.background(colors.selection),
        contentAlignment = Alignment.Center,
    ) {
        OmaText(
            text = "OMA / TUBE",
            color = colors.darkForeground,
            fontSize = 10.sp,
            chrome = true,
            letterSpacing = 1.sp,
        )
        if (!automation) {
            AsyncImage(
                model = videoThumbnailUrl(video),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Full-UI grid card (feed, history, and the Watch Next grid in both UIs).
 * Always chrome: the desktop cards use monospace regardless of simple mode.
 */
@Composable
fun FullVideoCard(
    video: Video,
    percent: Int,
    automation: Boolean,
    testTag: String,
    outlineTestTag: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    prefixText: String? = null,
    metaText: String? = null,
    showProgressLabel: Boolean = true,
    controls: (@Composable () -> Unit)? = null,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = LocalOmaColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val borderColor = if (pressed) colors.accent else colors.muted

    Box(modifier = modifier.testTag(testTag)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.glassPanel())
                .border(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier
                    .semantics {
                        this.contentDescription = contentDescription
                        role = Role.Button
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = onLongPress,
                        onClick = onOpen,
                    ),
            ) {
                OmaThumbnail(
                    video = video,
                    automation = automation,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
                ) {
                    OmaText(
                        text = video.title,
                        color = colors.foreground,
                        fontSize = 14.sp,
                        chrome = true,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (prefixText != null) {
                            OmaText(
                                text = prefixText,
                                color = colors.darkForeground,
                                fontSize = 10.sp,
                                chrome = true,
                            )
                            OmaText(
                                text = video.channelTitle,
                                color = colors.blue,
                                fontSize = 10.sp,
                                chrome = true,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            OmaText(
                                text = video.channelTitle,
                                color = colors.blue,
                                fontSize = 10.sp,
                                chrome = true,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (metaText != null) {
                                OmaText(
                                    text = metaText,
                                    color = colors.darkForeground,
                                    fontSize = 10.sp,
                                    chrome = true,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (showProgressLabel && percent >= 0) {
                        OmaText(
                            text = "$percent% watched",
                            color = colors.brightYellow,
                            fontSize = 10.sp,
                            chrome = true,
                        )
                    }
                }
                if (percent >= 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                                .height(3.dp)
                                .background(colors.brightYellow),
                        )
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth().height(3.dp))
                }
            }
            controls?.invoke()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(outlineTestTag)
                .border(1.dp, borderColor),
        )
    }
}

/** Simple-UI text row for feed and history. */
@Composable
fun SimpleVideoRow(
    video: Video,
    percent: Int,
    testTag: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    metaText: String,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = LocalOmaColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(modifier = modifier.fillMaxWidth().testTag(testTag)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (pressed) colors.selection else Color.Transparent)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onLongClick = onLongPress,
                    onClick = onOpen,
                )
                .padding(horizontal = 12.dp, vertical = 17.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    OmaText(
                        text = video.title,
                        color = colors.foreground,
                        fontSize = 21.sp,
                        chrome = false,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OmaText(
                            text = video.channelTitle,
                            color = colors.blue,
                            fontSize = 12.sp,
                            chrome = false,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        OmaText(
                            text = "\u00b7",
                            color = colors.darkForeground,
                            fontSize = 12.sp,
                            chrome = false,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        OmaText(
                            text = metaText,
                            color = colors.darkForeground,
                            fontSize = 12.sp,
                            chrome = false,
                            maxLines = 1,
                        )
                    }
                }
                if (percent >= 0) {
                    Spacer(Modifier.width(12.dp))
                    OmaText(
                        text = "$percent%",
                        color = colors.brightYellow,
                        fontSize = 13.sp,
                        chrome = false,
                        weight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.muted),
        )
    }
}
