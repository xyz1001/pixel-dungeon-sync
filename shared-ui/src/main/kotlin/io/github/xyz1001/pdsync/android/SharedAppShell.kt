package io.github.xyz1001.pdsync.android

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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.sp

/** Display metadata from the selected real game. No placeholder identity is supplied by the UI. */
data class SharedGameIdentity(val name: String, val version: String? = null) {
    val summary: String get() = name + version?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

internal data class SharedShellMetrics(val narrow: Boolean) {
    val outerHorizontal = if (narrow) 10.dp else 18.dp
    val outerVertical = if (narrow) 15.dp else 30.dp
    val horizontal = if (narrow) 20.dp else 32.dp
    val corner = if (narrow) 16.dp else 22.dp
    val headerTop = if (narrow) 24.dp else 28.dp
    val headerBottom = if (narrow) 12.dp else 18.dp
    val contentTop = if (narrow) 15.dp else 16.dp
    val contentBottom = if (narrow) 24.dp else 30.dp
    val footerVertical = if (narrow) 16.dp else 20.dp
    val titleSize = if (narrow) 22.sp else 25.sp
}

/**
 * The reference's single page shell. Hosts provide a bounded viewport, without their own padding,
 * scrolling or card. Only the content scrolls; header and bordered footer remain visible.
 */
@Composable
fun SharedAppShell(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    game: SharedGameIdentity? = null,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        val metrics = SharedShellMetrics(maxWidth <= 560.dp)
        Box(Modifier.fillMaxSize().padding(metrics.outerHorizontal, metrics.outerVertical),
            contentAlignment = Alignment.TopCenter) {
            Surface(
                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
                shape = RoundedCornerShape(metrics.corner),
                color = MaterialTheme.colors.surface,
                border = BorderStroke(1.dp, SharedUiColors.line),
                elevation = 0.dp,
            ) {
                SharedShellLayout(
                    minimumHeight = 568.dp,
                    header = { SharedPageHeader(title, eyebrow, game, metrics) },
                    body = {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(start = metrics.horizontal, end = metrics.horizontal,
                                top = metrics.contentTop, bottom = metrics.contentBottom), content = content)
                    },
                    footer = {
                        Column {
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
        val headerMax = (constraints.maxHeight - footerPlaceable.height).coerceAtLeast(0)
        val headerPlaceable = measurables[0].measure(loose.copy(maxHeight = headerMax))
        val fixedHeight = headerPlaceable.height + footerPlaceable.height
        val bodyMax = if (constraints.hasBoundedHeight) (constraints.maxHeight - fixedHeight).coerceAtLeast(0)
            else Constraints.Infinity
        val bodyMin = (minimumHeight.roundToPx().coerceAtMost(constraints.maxHeight) - fixedHeight)
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
private fun SharedPageHeader(title: String, eyebrow: String?, game: SharedGameIdentity?, metrics: SharedShellMetrics) {
    Column(Modifier.fillMaxWidth().padding(start = metrics.horizontal, end = metrics.horizontal,
        top = metrics.headerTop, bottom = metrics.headerBottom)) {
        if (!eyebrow.isNullOrBlank()) {
            Text(eyebrow, color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 16.sp,
                letterSpacing = 0.96.sp)
            Spacer(Modifier.height(8.dp))
        }
        Text(title, fontSize = metrics.titleSize, lineHeight = metrics.titleSize * 1.2f,
            fontWeight = FontWeight(650), letterSpacing = metrics.titleSize * -0.02f)
        if (game != null && game.name.isNotBlank()) {
            Text(game.summary, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.8.sp)
        }
    }
}

@Composable
fun SharedForm(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.widthIn(max = 510.dp).fillMaxWidth(), content = content)
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth().padding(bottom = 19.dp)) {
        Text(label, color = SharedUiColors.label, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                .border(if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colors.primary else SharedUiColors.fieldBorder,
                    RoundedCornerShape(9.dp))
                .background(MaterialTheme.colors.surface, RoundedCornerShape(9.dp))
                .padding(13.dp).semantics { contentDescription = label },
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
        )
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
) {
    val primary = MaterialTheme.colors.primary
    val foreground = if (style == SharedButtonStyle.PRIMARY) MaterialTheme.colors.onPrimary else primary
    val background = if (style == SharedButtonStyle.PRIMARY) primary else SharedUiColors.secondary
    val labelContent: @Composable RowScope.() -> Unit = {
        Text(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    }
    if (style == SharedButtonStyle.TEXT) {
        TextButton(onClick, modifier.heightIn(min = 46.dp), enabled = enabled,
            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = primary,
                disabledContentColor = primary.copy(alpha = .42f)), content = labelContent)
    } else {
        Button(onClick, modifier.heightIn(min = 46.dp), enabled = enabled,
            shape = RoundedCornerShape(10.dp), elevation = null,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = background, contentColor = foreground,
                disabledBackgroundColor = background.copy(alpha = .42f),
                disabledContentColor = foreground.copy(alpha = .42f)), content = labelContent)
    }
}
