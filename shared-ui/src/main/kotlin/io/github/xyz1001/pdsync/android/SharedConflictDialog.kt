package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Host-owned evidence token; presentation never invents overwrite or merge permission. */
data class SharedConfirmationState(
    val token: Long,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val confirmEnabled: Boolean = false,
    val secondaryLabel: String? = null,
    val secondaryEnabled: Boolean = false,
    val busy: Boolean = false,
    val details: String = "",
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
        BoxWithConstraints(Modifier.padding(16.dp)) {
            SharedConfirmationDialogBody(state, onConfirm, onSecondary, onDismiss,
                Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight), content)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    Surface(modifier, color = MaterialTheme.colors.surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SharedUiColors.line)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            if (state.message.isNotBlank()) Text(state.message, color = SharedUiColors.muted, fontSize = 14.sp, lineHeight = 22.sp)
            content()
            if (state.details.isNotBlank()) Text(state.details, color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SharedActionButton("取消", { interactions.dismiss(state.token) },
                    modifier = Modifier.widthIn(min = 90.dp).weight(1f), enabled = !state.busy,
                    style = SharedButtonStyle.SECONDARY)
                SharedActionButton(state.confirmLabel, { interactions.confirm(state.token) },
                    modifier = Modifier.widthIn(min = 90.dp).weight(1f), enabled = !state.busy && state.confirmEnabled)
                state.secondaryLabel?.let { label ->
                    SharedActionButton(label, { interactions.secondary(state.token) },
                        modifier = Modifier.widthIn(min = 90.dp).weight(1f), enabled = !state.busy && state.secondaryEnabled)
                }
            }
        }
    }
}
