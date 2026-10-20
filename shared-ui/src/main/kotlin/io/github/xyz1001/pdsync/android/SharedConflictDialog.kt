package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Host-owned evidence token; presentation never invents overwrite or merge permission. */
data class SharedConfirmationState @JvmOverloads constructor(
    val token: Long,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val confirmEnabled: Boolean = false,
    val secondaryLabel: String? = null,
    val secondaryEnabled: Boolean = false,
    val busy: Boolean = false,
    val details: String = "",
    val destructive: Boolean = false,
)

internal class SharedConfirmationInteractions(
    private val state: () -> SharedConfirmationState?,
    private val onConfirm: (Long) -> Unit,
    private val onSecondary: (Long) -> Unit,
    private val onDismiss: (Long) -> Unit,
) {
    fun confirm(token: Long) {
        val current = state() ?: return
        if (current.token == token && !current.busy && current.confirmEnabled) onConfirm(token)
    }
    fun secondary(token: Long) {
        val current = state() ?: return
        if (current.token == token && !current.busy && current.secondaryLabel != null && current.secondaryEnabled) onSecondary(token)
    }
    fun dismiss(token: Long) {
        val current = state() ?: return
        if (current.token == token && !current.busy) onDismiss(token)
    }
}

/** Reusable modal for Step 4 and home actions; its host owns confirmation and merge plans. */
@Composable
fun SharedConfirmationDialog(
    state: SharedConfirmationState,
    onConfirm: (Long) -> Unit,
    onSecondary: (Long) -> Unit = {},
    onDismiss: (Long) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val currentState by rememberUpdatedState(state)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val displayedToken = state.token
    Dialog(onDismissRequest = {
        if (currentState.token == displayedToken && !currentState.busy) currentDismiss(displayedToken)
    }, properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy,
        usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(horizontal = 19.dp, vertical = 24.dp)) {
            SharedConfirmationDialogBody(state, onConfirm, onSecondary, onDismiss,
                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight), content)
        }
    }
}

@Composable
internal fun SharedConfirmationDialogBody(
    state: SharedConfirmationState,
    onConfirm: (Long) -> Unit,
    onSecondary: (Long) -> Unit = {},
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val currentState by rememberUpdatedState(state)
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentSecondary by rememberUpdatedState(onSecondary)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val interactions = remember { SharedConfirmationInteractions({ currentState },
        { currentConfirm(it) }, { currentSecondary(it) }, { currentDismiss(it) }) }
    Surface(modifier, color = MaterialTheme.colors.surface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, SharedUiColors.fieldBorder)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A small window or the keyboard may reduce the dialog's height. Keep Cancel/Save/
            // Overwrite reachable while only the content (including maintenance details) scrolls.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.title, color = SharedUiColors.text, fontSize = 19.sp, lineHeight = 28.5.sp, fontWeight = FontWeight.Bold)
                if (state.message.isNotBlank()) {
                    if (state.destructive) {
                        Surface(color = Color(0xFFFFFBEB), contentColor = Color(0xFF855300),
                            border = BorderStroke(1.dp, Color(0xFFFFDEA9)), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SharedReferenceIcon("warning")
                                Text(state.message, fontSize = 13.sp, lineHeight = 19.5.sp)
                            }
                        }
                    } else Text(state.message, color = SharedUiColors.label, fontSize = 14.sp, lineHeight = 21.sp)
                }
                content()
                if (state.details.isNotBlank()) Text(state.details, color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SharedConfirmationActions(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                SharedActionButton(SharedMessages.CANCEL.text(), { interactions.dismiss(state.token) },
                    modifier = Modifier.widthIn(min = 90.dp), enabled = !state.busy,
                    style = if (state.secondaryLabel == null) SharedButtonStyle.SECONDARY else SharedButtonStyle.TEXT)
                SharedActionButton(state.confirmLabel, { interactions.confirm(state.token) },
                    modifier = Modifier.widthIn(min = 90.dp), enabled = !state.busy && state.confirmEnabled,
                    style = if (state.secondaryLabel == null) SharedButtonStyle.PRIMARY else SharedButtonStyle.SECONDARY)
                state.secondaryLabel?.let { label ->
                    SharedActionButton(label, { interactions.secondary(state.token) },
                        modifier = Modifier.widthIn(min = 90.dp), enabled = !state.busy && state.secondaryEnabled)
                }
            }
        }
    }
}

/** M3 reference flex: 1 0 auto: keep each label's natural width, wrap, then share spare space. */
@Composable
private fun SharedConfirmationActions(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else maxOf(constraints.minWidth, naturalWidths.sum() + gap * maxOf(0, measurables.size - 1))
        val rows = mutableListOf<MutableList<Int>>()
        var used = 0
        naturalWidths.forEachIndexed { index, naturalWidth ->
            val itemWidth = minOf(naturalWidth, width)
            if (rows.isEmpty() || (rows.last().isNotEmpty() && used + gap + itemWidth > width)) {
                rows += mutableListOf<Int>()
                used = 0
            }
            if (rows.last().isNotEmpty()) used += gap
            rows.last() += index
            used += itemWidth
        }
        val measuredRows = rows.map { row ->
            val spare = width - row.sumOf { minOf(naturalWidths[it], width) } - gap * (row.size - 1)
            row.mapIndexed { position, index ->
                val itemWidth = minOf(naturalWidths[index], width) + spare / row.size +
                    if (position < spare % row.size) 1 else 0
                measurables[index].measure(Constraints(minWidth = itemWidth, maxWidth = itemWidth,
                    maxHeight = constraints.maxHeight))
            }
        }
        val rowHeights = measuredRows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * maxOf(0, measuredRows.size - 1)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            measuredRows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + gap
                }
                y += rowHeights[rowIndex] + gap
            }
        }
    }
}
