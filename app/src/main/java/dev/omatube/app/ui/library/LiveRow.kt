package dev.omatube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.omatube.app.model.Video
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors

@Composable
fun LiveRow(
    live: List<Video>,
    avatarUrls: Map<String, String>,
    simple: Boolean,
    automation: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (Video) -> Unit,
) {
    if (live.isEmpty()) return
    val colors = LocalOmaColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        OmaText(
            text = "LIVE NOW",
            color = colors.brightRed,
            fontSize = 11.sp,
            chrome = false,
            weight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(9.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = live, key = { it.id }) { video ->
                LiveTile(
                    video = video,
                    avatarUrl = avatarUrls[video.channelId].orEmpty(),
                    simple = simple,
                    automation = automation,
                    onOpen = { onOpen(video) },
                )
            }
        }
    }
}

@Composable
private fun LiveTile(
    video: Video,
    avatarUrl: String,
    simple: Boolean,
    automation: Boolean,
    onOpen: () -> Unit,
) {
    val colors = LocalOmaColors.current
    // Full UI shrinks the live avatar by 15% (56 dp to 48 dp) and trims the
    // tile to keep the same 16 dp width and 11 dp height slack. Simple UI
    // keeps its 72x86 tile and 56 dp avatar.
    val tileWidth = if (simple) 72.dp else 64.dp
    val tileHeight = if (simple) 86.dp else 78.dp
    val avatarSize = if (simple) 56.dp else 48.dp
    Column(
        modifier = Modifier
            .width(tileWidth)
            .height(tileHeight)
            .testTag("liveVideo_${video.id}")
            .semantics {
                contentDescription = "Live video ${video.id} ${video.channelTitle} ${video.title}"
                role = Role.Button
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .background(colors.lighterBackground)
                .border(2.dp, colors.brightRed),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = if (automation) "" else avatarUrl
            val initial = video.channelTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            OmaText(
                text = initial,
                color = colors.foreground,
                fontSize = 20.sp,
                chrome = false,
                weight = FontWeight.SemiBold,
            )
            if (avatar.isNotEmpty()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        OmaText(
            text = video.channelTitle,
            color = colors.blue,
            fontSize = 11.sp,
            chrome = false,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
