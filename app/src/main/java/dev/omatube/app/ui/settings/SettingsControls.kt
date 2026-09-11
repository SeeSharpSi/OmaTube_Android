package dev.omatube.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.omatube.app.ui.theme.OmaTypography

@Composable
internal fun Modifier.settingsClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

internal fun settingsTextStyle(
    color: Color,
    chrome: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
): TextStyle = TextStyle(
    color = color,
    fontSize = fontSize,
    fontFamily = OmaTypography.family(chrome),
    fontWeight = weight,
    letterSpacing = letterSpacing,
)

@Composable
internal fun SettingsDivider(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
internal fun SettingsSectionLabel(
    text: String,
    palette: SettingsPalette,
    chrome: Boolean,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
) {
    BasicText(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = settingsTextStyle(palette.ink, chrome, fontSize, FontWeight.SemiBold),
    )
}

@Composable
internal fun SettingsButton(
    text: String,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    flat: Boolean = false,
    danger: Boolean = false,
    chrome: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val textColor = when {
        !enabled -> palette.mutedInk
        danger -> palette.danger
        else -> palette.ink
    }
    val background = when {
        flat -> Color.Transparent
        danger -> palette.popup
        else -> palette.softFill
    }
    val borderColor = when {
        !enabled -> palette.rule
        danger -> palette.danger
        flat -> Color.Transparent
        else -> palette.rule
    }
    Box(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .heightIn(min = 36.dp)
            .background(background)
            .border(1.dp, borderColor)
            .settingsClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = settingsTextStyle(textColor, chrome, 13.sp, FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    password: Boolean = false,
    chrome: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    testTag: String? = null,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        !enabled -> palette.rule
        focused -> palette.accent
        else -> palette.rule
    }
    val textColor = if (enabled) palette.ink else palette.mutedInk
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = settingsTextStyle(textColor, chrome, 13.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onSubmit?.invoke() },
            onGo = { onSubmit?.invoke() },
        ),
        cursorBrush = SolidColor(palette.accent),
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .height(40.dp)
            .background(palette.paper)
            .border(1.dp, borderColor)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(
                        text = placeholder,
                        style = settingsTextStyle(palette.mutedInk, chrome, 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun SettingsCheckbox(
    checked: Boolean,
    text: String,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chrome: Boolean = true,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .settingsClickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(
                    width = if (checked) 2.dp else 1.dp,
                    color = when {
                        !enabled -> palette.mutedInk
                        checked -> palette.accent
                        else -> palette.mutedInk
                    },
                )
                .background(if (checked) palette.softFill else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Box(Modifier.size(7.dp).background(palette.accent))
            }
        }
        Spacer(Modifier.width(8.dp))
        BasicText(
            text = text,
            style = settingsTextStyle(
                color = if (enabled) palette.ink else palette.mutedInk,
                chrome = chrome,
                fontSize = if (chrome) 12.sp else 13.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsDropdown(
    options: List<String>,
    selectedIndex: Int,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chrome: Boolean = false,
    testTag: String? = null,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    var heightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val chevronColor = when {
        !enabled -> palette.mutedInk
        expanded -> palette.ink
        else -> palette.mutedInk
    }
    Box {
        Row(
            modifier = modifier
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { widthPx = it.width; heightPx = it.height }
                .background(if (enabled && expanded) palette.softFill else palette.paper)
                .border(
                    width = 1.dp,
                    color = when {
                        !enabled -> palette.rule
                        expanded -> palette.mutedInk
                        else -> palette.rule
                    },
                )
                .settingsClickable(enabled = enabled) { expanded = true }
                .padding(start = 12.dp, end = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = options.getOrElse(selectedIndex) { options.firstOrNull().orEmpty() },
                style = settingsTextStyle(
                    color = if (enabled) palette.ink else palette.mutedInk,
                    chrome = chrome,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(10.dp, 6.dp),
        ) {
            val path = Path().apply {
                moveTo(0f, 0.5f)
                lineTo(size.width / 2f, size.height - 0.5f)
                lineTo(size.width, 0.5f)
            }
            drawPath(
                path = path,
                color = chevronColor,
                style = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
                offset = IntOffset(0, heightPx),
            ) {
                val popupWidth = with(density) { widthPx.toDp() }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .width(popupWidth)
                        .heightIn(max = 320.dp)
                        .background(palette.popup)
                        .border(1.dp, palette.rule)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { index, label ->
                        val selected = index == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (testTag != null) {
                                        Modifier.testTag("${testTag}_option_$index")
                                    } else {
                                        Modifier
                                    },
                                )
                                .background(if (selected) palette.softFill else Color.Transparent)
                                .settingsClickable {
                                    expanded = false
                                    onSelect(index)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(if (selected) palette.accent else Color.Transparent),
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                text = label,
                                style = settingsTextStyle(
                                    color = if (selected) palette.ink else palette.mutedInk,
                                    chrome = chrome,
                                    fontSize = 13.sp,
                                    weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsTabs(
    tabs: List<Pair<String, String>>,
    selectedIndex: Int,
    palette: SettingsPalette,
    chrome: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                if (chrome) tint(palette.paper, palette.accent.copy(alpha = 0.035f)) else palette.paper,
            ),
    ) {
        val minTab = 96.dp
        val tabWidth = maxOf(minTab, maxWidth / tabs.size)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scroll),
        ) {
            tabs.forEachIndexed { index, tab ->
                val (label, tag) = tab
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .testTag(tag)
                        .settingsClickable { onSelect(index) },
                ) {
                    BasicText(
                        text = if (chrome) label.uppercase() else label,
                        style = settingsTextStyle(
                            color = if (selected) palette.ink else palette.softInk,
                            chrome = chrome,
                            fontSize = 13.sp,
                            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = if (chrome) 0.5.sp else 0.sp,
                        ),
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(palette.accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsStepper(
    value: Int,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    range: IntRange = SettingsLogic.CUTOFF_MIN..SettingsLogic.CUTOFF_MAX,
    testTag: String? = null,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .height(40.dp)
            .background(palette.paper)
            .border(1.dp, palette.rule),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(
            symbol = "\u2212",
            palette = palette,
            enabled = value > range.first,
            onClick = { onChange((value - 1).coerceIn(range.first, range.last)) },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = value.toString(),
                style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
            )
        }
        StepperButton(
            symbol = "+",
            palette = palette,
            enabled = value < range.last,
            onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
        )
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    palette: SettingsPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(32.dp)
            .fillMaxHeight()
            .settingsClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = symbol,
            style = settingsTextStyle(
                color = if (enabled) palette.ink else palette.mutedInk,
                chrome = false,
                fontSize = 12.sp,
                weight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
internal fun SettingsHeader(
    palette: SettingsPalette,
    chrome: Boolean,
    simpleUi: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (simpleUi) 64.dp else 62.dp)
            .background(palette.panel),
    ) {
        BasicText(
            text = "Config",
            style = settingsTextStyle(
                color = palette.ink,
                chrome = chrome,
                fontSize = if (simpleUi) 21.sp else 17.sp,
                weight = FontWeight.SemiBold,
                letterSpacing = if (chrome) 1.4.sp else 0.sp,
            ),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .size(38.dp)
                .then(
                    if (chrome) Modifier.border(1.dp, palette.rule) else Modifier,
                )
                .testTag("settingsCloseButton")
                .settingsClickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                drawLine(
                    color = palette.softInk,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.3f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = palette.softInk,
                    start = Offset(size.width, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.3f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.rule),
        )
    }
}

@Composable
internal fun SettingsErrorBar(
    message: String,
    palette: SettingsPalette,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.errorFill)
            .border(1.dp, palette.danger.copy(alpha = 0.42f))
            .padding(9.dp)
            .testTag("settingsError"),
        verticalAlignment = Alignment.Top,
    ) {
        BasicText(
            text = message,
            style = settingsTextStyle(palette.danger, chrome = false, fontSize = 12.sp),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(9.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(palette.errorFill)
                .border(1.dp, palette.danger)
                .testTag("settingsErrorClose")
                .settingsClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "x",
                style = settingsTextStyle(palette.danger, chrome = true, fontSize = 16.sp),
            )
        }
    }
}

@Composable
internal fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    palette: SettingsPalette,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .width(360.dp)
                .background(palette.panel)
                .border(1.dp, palette.rule)
                .padding(18.dp),
        ) {
            BasicText(
                text = title,
                style = settingsTextStyle(palette.ink, chrome = false, fontSize = 15.sp, weight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = message,
                style = settingsTextStyle(palette.ink, chrome = false, fontSize = 13.sp),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SettingsButton(
                    text = "Cancel",
                    palette = palette,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                SettingsButton(
                    text = confirmLabel,
                    palette = palette,
                    danger = true,
                    testTag = "settingsConfirmYes",
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun SettingsDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color))
}
