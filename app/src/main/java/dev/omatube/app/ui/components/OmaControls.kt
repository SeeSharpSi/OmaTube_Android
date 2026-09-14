package dev.omatube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omatube.app.ui.theme.OmaTypography
import kotlinx.coroutines.delay

val OmaSpinnerFrames: List<String> = listOf(
    "\u280b", "\u2819", "\u2839", "\u2838", "\u283c",
    "\u2834", "\u2826", "\u2827", "\u2807", "\u280f",
)

@Composable
fun OmaText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    chrome: Boolean,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    letterSpacing: TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontFamily = OmaTypography.family(chrome),
            fontWeight = weight,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun OmaDivider(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun OmaNavButton(
    kind: OmaGlyphKind,
    active: Boolean,
    contentDescription: String,
    accent: Color,
    rule: Color,
    softFill: Color,
    panel: Color,
    mutedInk: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    buttonSize: Dp = 34.dp,
    glyphSize: Dp = 15.dp,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val foreground = when {
        loading -> ink
        !enabled -> mutedInk
        active -> panel
        pressed -> accent
        kind == OmaGlyphKind.REFRESH && !active -> ink
        else -> mutedInk
    }
    val background = when {
        active -> accent
        pressed -> softFill
        else -> Color.Transparent
    }
    val border = if (active) accent else rule
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .size(buttonSize)
            .background(background)
            .border(1.dp, border)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            OmaSpinner(
                active = true,
                color = foreground,
                chrome = true,
                fontSize = (buttonSize.value * 0.42f).sp,
            )
        } else {
            OmaGlyph(kind = kind, color = foreground, size = glyphSize)
        }
    }
}

@Composable
fun OmaSpinner(
    active: Boolean,
    color: Color,
    chrome: Boolean,
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier,
) {
    val frame = rememberOmaSpinnerFrame(active)
    if (active) {
        OmaText(
            text = OmaSpinnerFrames[frame],
            color = color,
            fontSize = fontSize,
            chrome = chrome,
            modifier = modifier,
            maxLines = 1,
        )
    }
}

@Composable
fun rememberOmaSpinnerFrame(active: Boolean, intervalMillis: Long = 80L): Int {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, intervalMillis) {
        if (!active) {
            frame = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(intervalMillis)
            frame = (frame + 1) % OmaSpinnerFrames.size
        }
    }
    return frame
}

@Composable
fun OmaErrorBanner(
    message: String,
    panel: Color,
    danger: Color,
    paper: Color,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = modifier
            .testTag("libraryError")
            .fillMaxWidth()
            .background(panel)
            .border(1.dp, tint(paper, danger.copy(alpha = 0.48f)))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmaText(
            text = message,
            color = danger,
            fontSize = 12.sp,
            chrome = false,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .testTag("libraryErrorDismiss")
                .size(28.dp)
                .border(1.dp, danger)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            OmaText(text = "x", color = danger, fontSize = 16.sp, chrome = true)
        }
    }
}

@Composable
fun OmaStatusPill(
    status: String,
    active: Boolean,
    panel: Color,
    rule: Color,
    ink: Color,
    mutedInk: Color,
    chrome: Boolean,
    modifier: Modifier = Modifier,
) {
    if (status.isEmpty() && !active) return
    Row(
        modifier = modifier
            .testTag("libraryStatus")
            .background(panel)
            .border(1.dp, rule)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmaSpinner(active = active, color = ink, chrome = chrome, fontSize = 15.sp)
        OmaText(
            text = status.ifEmpty { "Working..." },
            color = mutedInk,
            fontSize = 12.sp,
            chrome = chrome,
            maxLines = 1,
        )
    }
}
