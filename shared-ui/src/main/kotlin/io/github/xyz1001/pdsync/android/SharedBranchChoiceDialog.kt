package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pdsync.i18n.SharedMessages

/** The existing observation-bound chooser, presented over home instead of a separate page. */
@Composable
fun SharedBranchChoiceDialog(state: SharedBranchChoiceState, actions: SharedBranchChoiceActions) {
    SharedConnectionTheme {
        val interactions = rememberSharedBranchChoiceDialogInteractions(state, actions)
        Dialog(onDismissRequest = interactions::dismissBranchDialog,
            properties = DialogProperties(dismissOnBackPress = !state.busy,
                dismissOnClickOutside = !state.busy, usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.padding(horizontal = 19.dp, vertical = 24.dp)) {
                SharedBranchChoiceDialogBody(state, interactions,
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight))
            }
        }
    }
}

/** A closed modal cannot retain live write callbacks when it is reopened. */
@Composable
internal fun rememberSharedBranchChoiceDialogInteractions(
    state: SharedBranchChoiceState,
    actions: SharedBranchChoiceActions,
): SharedBranchChoiceInteractions {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedBranchChoiceInteractions({ currentState }, { currentActions }) }
    DisposableEffect(interactions) { onDispose { interactions.dispose() } }
    return interactions
}

/** Back/outside dismissal cancels the inner confirmation before leaving the chooser. */
internal fun SharedBranchChoiceInteractions.dismissBranchDialog() {
    presentation?.let { cancel(it.token) } ?: back()
}

/** Production body shared by the modal and offscreen layout/input tests. */
@Composable
internal fun SharedBranchChoiceDialogBody(
    state: SharedBranchChoiceState,
    interactions: SharedBranchChoiceInteractions,
    modifier: Modifier = Modifier,
) {
    // Keep the existing expandable details behavior, including across confirmation cancellation.
    var expanded by remember(state.observation, state.details) { mutableStateOf(false) }
    val confirmation = interactions.presentation
    if (confirmation != null) {
        SharedConfirmationDialogBody(confirmation, interactions::confirm, interactions::merge,
            interactions::cancel, modifier)
    } else {
        SharedConfirmationDialogBody(
            SharedConfirmationState(0, SharedMessages.CHOOSE_CLOUD_SAVE.text(),
                if (state.hasBranches) SharedMessages.CHOOSE_PROGRESS.text() else SharedMessages.BRANCH_CHANGED.text(),
                if (state.hasBranches) SharedMessages.HANDLE_CONFLICT.text() else SharedMessages.COMPARE_AGAIN.text(),
                confirmEnabled = state.primaryEnabled, busy = state.busy),
            onConfirm = { interactions.primary() }, onDismiss = { interactions.back() },
            modifier = modifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.branches.forEach { branch ->
                    ComparisonChoiceCard(branch.name, branch.summary, state.selectedId == branch.snapshotId,
                        enabled = state.canInteract && state.connected && state.hasBranches,
                        onClick = { interactions.select(branch.snapshotId) })
                }
            }
            if (state.notice.isNotBlank()) Text(state.notice, Modifier.fillMaxWidth(),
                color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 19.5.sp)
            if (state.canPreserveLocal && !state.cloudOverwrite) SharedActionButton(SharedMessages.UPLOAD_KEEP_BOTH.text(),
                interactions::preserveLocal, enabled = state.preserveEnabled, style = SharedButtonStyle.SECONDARY)
            if (state.details.isNotBlank()) {
                SharedActionButton(if (expanded) SharedMessages.HIDE_DETAILS.text() else SharedMessages.DETAILS.text(),
                    { expanded = !expanded }, style = SharedButtonStyle.TEXT)
                if (expanded) Text(state.details, color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
