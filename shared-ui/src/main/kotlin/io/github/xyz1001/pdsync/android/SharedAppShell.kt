package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.sp

/** Android supplies the existing export action; desktop leaves it absent. */
class SharedDiagnosticAction(val onClick: Runnable) {
    var enabled by mutableStateOf(true)
}
val LocalSharedDiagnosticAction = staticCompositionLocalOf<SharedDiagnosticAction?> { null }

/** Display metadata from the selected real game. No placeholder identity is supplied by the UI. */
data class SharedGameIdentity @JvmOverloads constructor(
    val name: String, val version: String? = null, val icon: ImageBitmap? = null,
) {
    val summary: String get() = name + version?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

internal data class SharedShellMetrics(val narrow: Boolean, val compactVertical: Boolean = false) {
    val outerHorizontal = if (narrow) 8.dp else 16.dp
    val outerVertical = if (compactVertical) 4.dp else if (narrow) 12.dp else 24.dp
    val horizontal = if (narrow) 20.dp else 30.dp
    val corner = if (narrow) 16.dp else 24.dp
    val headerTop = if (compactVertical) 12.dp else if (narrow) 18.dp else 22.dp
    val headerBottom = if (compactVertical) 10.dp else if (narrow) 14.dp else 18.dp
    val contentTop = if (compactVertical) 12.dp else if (narrow) 18.dp else 22.dp
    val contentBottom = if (compactVertical) 16.dp else if (narrow) 24.dp else 28.dp
    val footerVertical = if (compactVertical) 10.dp else if (narrow) 14.dp else 16.dp
    val titleSize = if (narrow) 21.sp else 24.sp
}

/**
 * The reference's single page shell. Hosts provide a bounded viewport, without their own padding,
 * scrolling or card. Only the content scrolls; header and an optional bordered footer remain visible.
 */
@Composable
fun SharedAppShell(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    game: SharedGameIdentity? = null,
    onboardingStep: Int? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        // Android's host has already removed system-bar and IME insets. Keep its
        // panel bounded by that available height; desktop retains the reference card geometry.
        val androidViewport = LocalSharedInputViewport.current != null
        val metrics = SharedShellMetrics(maxWidth <= 600.dp, compactVertical = androidViewport)
        // On a very short Android keyboard viewport, fixed header/footer can leave no room
        // for even one field. Scroll the header with the body in that case, while keeping
        // the existing footer reachable above the keyboard. Desktop remains unchanged.
        val scrollHeaderWithBody = LocalSharedInputViewport.current != null &&
            maxHeight < 360.dp * LocalDensity.current.fontScale
        val bodyScroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(metrics.outerHorizontal, metrics.outerVertical),
            contentAlignment = Alignment.TopCenter) {
            Surface(
                modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth()
                    .then(if (androidViewport) Modifier.fillMaxHeight() else Modifier),
                shape = RoundedCornerShape(metrics.corner),
                color = MaterialTheme.colors.surface,
                border = BorderStroke(1.dp, SharedUiColors.fieldBorder),
                elevation = 2.dp,
            ) {
                val pageHeader: @Composable () -> Unit = {
                    // Later setup pages focus on their task; the selected identity remains on home.
                    val headerGame = game.takeIf { onboardingStep == null }
                    Column {
                        SharedPageHeader(title, eyebrow, headerGame, metrics,
                            LocalSharedDiagnosticAction.current.takeIf { onboardingStep in 1..2 && !eyebrow.isNullOrBlank() }, headerActions)
                        Divider(color = SharedUiColors.line)
                    }
                }
                SharedShellLayout(
                    minimumHeight = if (androidViewport) 0.dp else 600.dp,
                    header = { if (!scrollHeaderWithBody) pageHeader() },
                    body = {
                        Column(Modifier.fillMaxWidth().verticalScroll(bodyScroll)) {
                            if (scrollHeaderWithBody) pageHeader()
                            Column(Modifier.fillMaxWidth()
                                .padding(start = metrics.horizontal, end = metrics.horizontal,
                                    top = metrics.contentTop, bottom = metrics.contentBottom), content = content)
                        }
                    },
                    footer = {
                        if (footer != null) Column(Modifier.background(SharedUiColors.background)) {
                            Divider(color = SharedUiColors.line, thickness = 1.dp)
                            Row(Modifier.fillMaxWidth().padding(horizontal = metrics.horizontal,
                                vertical = metrics.footerVertical), verticalAlignment = Alignment.CenterVertically,
                                content = footer)
                        }
                    },
                )
            }
        }
    }
}

/** Measures real text first, then gives the scroll region the remaining bounded height. */
@Composable
private fun SharedShellLayout(
    minimumHeight: Dp,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    Layout(modifier = Modifier.padding(1.dp), content = {
        Box(Modifier.fillMaxWidth()) { header() }
        Box(Modifier.fillMaxWidth()) { body() }
        Box(Modifier.fillMaxWidth()) { footer() }
    }) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        // Footer is measured first so short windows and the IME never push its actions offscreen.
        val footerPlaceable = measurables[2].measure(loose)
        val headerMax = if (constraints.hasBoundedHeight)
            (constraints.maxHeight - footerPlaceable.height).coerceAtLeast(0) else Constraints.Infinity
        val headerPlaceable = measurables[0].measure(loose.copy(maxHeight = headerMax))
        val fixedHeight = headerPlaceable.height + footerPlaceable.height
        val bodyMax = if (constraints.hasBoundedHeight) (constraints.maxHeight - fixedHeight).coerceAtLeast(0)
            else Constraints.Infinity
        val bodyMin = (maxOf(minimumHeight.roundToPx(), constraints.minHeight).coerceAtMost(constraints.maxHeight) - fixedHeight)
            .coerceIn(0, bodyMax)
        val bodyPlaceable = measurables[1].measure(loose.copy(minHeight = bodyMin, maxHeight = bodyMax))
        val width = constraints.constrainWidth(maxOf(headerPlaceable.width, bodyPlaceable.width, footerPlaceable.width))
        val height = constraints.constrainHeight(fixedHeight + bodyPlaceable.height)
        layout(width, height) {
            headerPlaceable.placeRelative(0, 0)
            bodyPlaceable.placeRelative(0, headerPlaceable.height)
            footerPlaceable.placeRelative(0, height - footerPlaceable.height)
        }
    }
}

@Composable
private fun SharedPageHeader(title: String, eyebrow: String?, game: SharedGameIdentity?, metrics: SharedShellMetrics,
    diagnosticAction: SharedDiagnosticAction?, actions: @Composable RowScope.() -> Unit) {
    val titleStack: @Composable () -> Unit = {
        Column {
            if (!eyebrow.isNullOrBlank()) {
                Text(eyebrow, color = MaterialTheme.colors.primary,
                    fontSize = 12.sp, lineHeight = 24.sp,
                    letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, color = SharedUiColors.text, fontSize = metrics.titleSize, lineHeight = metrics.titleSize * 1.5f,
                fontWeight = FontWeight.Bold, letterSpacing = metrics.titleSize * -0.01f)
            if (game != null && game.name.isNotBlank()) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SharedReferenceIcon("game", Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    game.icon?.let {
                        Image(it, contentDescription = null, modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(game.summary, color = androidx.compose.ui.graphics.Color(0xFF4B6268), fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    val actionRow: @Composable RowScope.() -> Unit = {

        if (diagnosticAction != null) {
            Spacer(Modifier.width(8.dp))
            SharedDiagnosticExportButton(diagnosticAction)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
    val padding = Modifier.fillMaxWidth().padding(start = metrics.horizontal, end = metrics.horizontal,
        top = metrics.headerTop, bottom = metrics.headerBottom)
    // Large text must not let the existing action squeeze the title into one-character columns.
    if (metrics.narrow && LocalDensity.current.fontScale >= 1.5f) {
        Column(padding) {
            titleStack()
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actionRow)
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { titleStack() }
            actionRow()
        }
    }
}

/** Existing export action, styled from the current local v21 .btn-m3.btn-text reference. */
@Composable
private fun SharedDiagnosticExportButton(action: SharedDiagnosticAction) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (action.enabled && (hovered || pressed)) Color(0xFFF1F5F9) else Color.Transparent, tween(150, easing = CubicBezierEasing(.25f, .1f, .25f, 1f)))
    val scale by animateFloatAsState(if (action.enabled && pressed) .98f else 1f, tween(150, easing = CubicBezierEasing(.25f, .1f, .25f, 1f)))
    Row(Modifier.graphicsLayer {
            alpha = if (action.enabled) 1f else .4f
            scaleX = scale; scaleY = scale
        }.heightIn(min = 36.dp)
        .background(background, RoundedCornerShape(50))
        .border(1.dp, Color.Transparent, RoundedCornerShape(50))
        .hoverable(interaction, enabled = action.enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = action.enabled,
            role = Role.Button, onClick = action.onClick::run)
        // CSS padding plus the transparent 1px border; no tonal fill, outline or shadow.
        .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.primary) {
            SharedReferenceIcon("log", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(SharedMessages.EXPORT_LOGS.text(), color = MaterialTheme.colors.primary, fontSize = 14.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
        }
    }
}

@Composable
fun SharedForm(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), content = content)
}

/** The label sits above the field, as in the reference, rather than in an outline notch. */
@Composable
fun SharedTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    supportingText: String? = null,
    placeholder: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Keep the IME's selection and composing region, including during visibility toggles.
    // External credential clears replace text; ordinary controller echoes do not reset editing.
    var editing by remember { mutableStateOf(TextFieldValue(value)) }
    val fieldValue = if (editing.text == value) editing else editing.copy(text = value, composition = null)
    androidx.compose.runtime.SideEffect { if (editing != fieldValue) editing = fieldValue }
    Column(modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Column(Modifier.fillMaxWidth().keepFocusedFieldVisible(focused)) {
        Text(label, color = SharedUiColors.label, fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = fieldValue,
            onValueChange = { next ->
                editing = next
                if (next.text != value) onValueChange(next.text)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                .border(if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colors.primary else SharedUiColors.fieldBorder,
                    RoundedCornerShape(12.dp))
                .background(MaterialTheme.colors.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp).heightIn(min = 20.dp * LocalDensity.current.fontScale).semantics { contentDescription = label },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.body2.copy(
                color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 1f else .42f),
                fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                            Text(placeholder, color = SharedUiColors.muted, fontSize = 12.sp,
                                lineHeight = 20.sp, maxLines = 1)
                        }
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
        }
        if (!supportingText.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(supportingText, color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

enum class SharedButtonStyle { PRIMARY, SECONDARY, TEXT }

@Composable
fun SharedActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SharedButtonStyle = SharedButtonStyle.PRIMARY,
    icon: String? = null,
) {
    val primary = MaterialTheme.colors.primary
    val foreground = if (style == SharedButtonStyle.PRIMARY) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
    val background = if (style == SharedButtonStyle.PRIMARY) primary else SharedUiColors.secondary
    val labelContent: @Composable RowScope.() -> Unit = {
        if (icon != null) { SharedReferenceIcon(icon); Spacer(Modifier.width(8.dp)) }
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    }
    if (style == SharedButtonStyle.TEXT) {
        TextButton(onClick, modifier.heightIn(min = 42.dp), enabled = enabled,
            shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = primary,
                disabledContentColor = primary.copy(alpha = .42f)), content = labelContent)
    } else {
        Button(onClick, modifier.heightIn(min = 42.dp), enabled = enabled,
            shape = RoundedCornerShape(50),
            elevation = if (style == SharedButtonStyle.PRIMARY) ButtonDefaults.elevation(defaultElevation = 1.dp, pressedElevation = 2.dp, disabledElevation = 0.dp) else null,
            border = if (style == SharedButtonStyle.SECONDARY) BorderStroke(1.dp, SharedUiColors.fieldBorder) else null,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = background, contentColor = foreground,
                disabledBackgroundColor = background.copy(alpha = .42f),
                disabledContentColor = if (style == SharedButtonStyle.PRIMARY) androidx.compose.ui.graphics.Color.White else foreground.copy(alpha = .45f)), content = labelContent)
    }
}
