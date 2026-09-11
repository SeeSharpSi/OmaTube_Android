package dev.omatube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omatube.app.ui.components.OmaSpinner
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors

@Composable
fun LoadMoreFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    chrome: Boolean,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit,
) {
    if (!loadingMore && !hasMore) return
    val colors = LocalOmaColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("loadMoreFooter")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = hasMore && !loadingMore,
                onClick = onLoadMore,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loadingMore) {
            OmaSpinner(active = true, color = colors.foreground, chrome = chrome, fontSize = 16.sp)
        } else {
            OmaText(
                text = "Load more",
                color = colors.darkForeground,
                fontSize = 12.sp,
                chrome = chrome,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    chrome: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOmaColors.current
    Column(
        modifier = modifier
            .widthIn(max = 430.dp)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OmaText(
            text = title,
            color = colors.foreground,
            fontSize = 19.sp,
            chrome = chrome,
            weight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        OmaText(
            text = body,
            color = colors.darkForeground,
            fontSize = 13.sp,
            chrome = chrome,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

data class OmaMenuItem(
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun OmaPopupMenu(
    expanded: Boolean,
    offset: androidx.compose.ui.unit.IntOffset,
    chrome: Boolean,
    onDismiss: () -> Unit,
    items: List<OmaMenuItem>,
) {
    if (!expanded) return
    val colors = LocalOmaColors.current
    androidx.compose.ui.window.Popup(
        onDismissRequest = onDismiss,
        offset = offset,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp)
                .background(colors.lighterBackground)
                .border(1.dp, colors.muted),
        ) {
            items.forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = {
                                onDismiss()
                                item.onClick()
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    OmaText(
                        text = item.label,
                        color = if (item.danger) colors.red else colors.foreground,
                        fontSize = 13.sp,
                        chrome = chrome,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun QueueControls(
    videoId: String,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    chrome: Boolean,
    modifier: Modifier = Modifier,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlBox(
            label = "\u2191",
            testTag = "watchNextUp_$videoId",
            contentDescription = "Move up $videoId",
            enabled = canMoveUp,
            fontSize = 13.sp,
            chrome = false,
            onClick = { onMove(position - 1) },
        )
        ControlBox(
            label = "\u2193",
            testTag = "watchNextDown_$videoId",
            contentDescription = "Move down $videoId",
            enabled = canMoveDown,
            fontSize = 13.sp,
            chrome = false,
            onClick = { onMove(position + 1) },
        )
        Spacer(Modifier.width(6.dp))
        ControlBox(
            label = "REMOVE",
            testTag = "watchNextRemove_$videoId",
            contentDescription = "Remove from Watch Next $videoId",
            enabled = true,
            fontSize = 10.sp,
            chrome = true,
            onClick = onRemove,
        )
    }
}

@Composable
private fun ControlBox(
    label: String,
    testTag: String,
    contentDescription: String,
    enabled: Boolean,
    fontSize: TextUnit,
    chrome: Boolean,
    minWidth: Dp = 34.dp,
    onClick: () -> Unit,
) {
    val colors = LocalOmaColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .testTag(testTag)
            .widthIn(min = minWidth)
            .height(30.dp)
            .background(if (pressed && enabled) colors.selection else Color.Transparent)
            .border(1.dp, colors.muted)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        OmaText(
            text = label,
            color = if (enabled) colors.foreground else colors.darkForeground,
            fontSize = fontSize,
            chrome = chrome,
            maxLines = 1,
        )
    }
}
