package dev.omatube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.omatube.app.model.Category
import dev.omatube.app.ui.components.OmaText
import dev.omatube.app.ui.theme.LocalOmaColors
import kotlin.math.roundToInt

@Composable
fun CategoryBar(
    categories: List<Category>,
    selectedCategoryId: Long,
    chrome: Boolean,
    barHeight: Dp,
    buttonHeight: Dp,
    horizontalPadding: Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    onCategory: (Long) -> Unit,
    onMoveCategory: (Long, Int) -> Unit,
) {
    val colors = LocalOmaColors.current
    val scrollState = rememberScrollState()
    val centers = remember { mutableStateMapOf<Long, Float>() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pendingTarget by remember { mutableStateOf(-1) }
    var dropCenter by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .horizontalScroll(scrollState, enabled = draggingId == null),
    ) {
        Row(
            modifier = Modifier.height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEachIndexed { index, category ->
                key(category.id) {
                    val dragging = draggingId == category.id
                    val chipModifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            centers[category.id] =
                                coordinates.positionInParent().x + coordinates.size.width / 2f
                        }
                        .zIndex(if (dragging) 1f else 0f)
                        .offset {
                            if (dragging) IntOffset(dragOffset.roundToInt(), 0) else IntOffset.Zero
                        }
                        .pointerInput(category.id, categories.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = category.id
                                    dragOffset = 0f
                                    pendingTarget = index
                                    dropCenter = centers[category.id]
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                    val center = (centers[category.id] ?: 0f) + dragOffset
                                    val otherCenters = categories
                                        .asSequence()
                                        .filter { it.id != category.id }
                                        .mapNotNull { centers[it.id] }
                                        .toList()
                                    pendingTarget = categoryTargetIndex(otherCenters, center)
                                    dropCenter = center
                                },
                                onDragEnd = {
                                    val target = pendingTarget
                                    draggingId = null
                                    dragOffset = 0f
                                    dropCenter = null
                                    if (target >= 0 && target != index) {
                                        onMoveCategory(category.id, target)
                                    }
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                    dropCenter = null
                                },
                            )
                        }
                    CategoryChip(
                        label = category.name,
                        selected = selectedCategoryId == category.id,
                        chrome = chrome,
                        buttonHeight = buttonHeight,
                        horizontalPadding = horizontalPadding,
                        fontSize = fontSize,
                        testTag = "categoryButton_${category.id}",
                        contentDescription = "Category ${category.id} ${category.name}",
                        modifier = chipModifier,
                        onClick = {
                            onCategory(toggledCategorySelection(selectedCategoryId, category.id))
                        },
                    )
                }
            }
        }
        val indicator = dropCenter
        if (indicator != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(indicator.roundToInt(), 0) }
                    .width(2.dp)
                    .height(buttonHeight - 2.dp)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    chrome: Boolean,
    buttonHeight: Dp,
    horizontalPadding: Dp,
    fontSize: TextUnit,
    testTag: String,
    contentDescription: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalOmaColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        selected -> colors.accent
        pressed -> colors.selection
        else -> colors.lighterBackground
    }
    val border = if (selected || pressed) colors.accent else colors.muted
    val textColor = if (selected) colors.lighterBackground else colors.foreground
    Box(
        modifier = modifier
            .testTag(testTag)
            .height(buttonHeight)
            .border(1.dp, border)
            .background(background)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        OmaText(
            text = label,
            color = textColor,
            fontSize = fontSize,
            chrome = chrome,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
