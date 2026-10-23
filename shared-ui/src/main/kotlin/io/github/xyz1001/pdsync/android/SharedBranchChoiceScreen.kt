package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun interface SharedBranchChoiceLayoutObserver { fun element(name: String, bounds: Rect) }
internal val LocalSharedBranchChoiceLayoutObserver = staticCompositionLocalOf<SharedBranchChoiceLayoutObserver?> { null }

/** The multi-head extension of the reference's single Handle Conflict action. No platform I/O. */
@Composable
fun SharedBranchChoiceScreen(state: SharedBranchChoiceState, actions: SharedBranchChoiceActions) {
    if (state.cloudOverwrite) {
        SharedBranchChoiceDialog(state, actions)
        return
    }
    SharedConnectionTheme {
        val currentState by rememberUpdatedState(state)
        val currentActions by rememberUpdatedState(actions)
        val interactions = remember { SharedBranchChoiceInteractions({ currentState }, { currentActions }) }
        DisposableEffect(interactions) { onDispose { interactions.dispose() } }
        val observer = LocalSharedBranchChoiceLayoutObserver.current
        val confirmation = interactions.presentation
        SharedAppShell(title = SharedMessages.CHOOSE_CLOUD_SAVE.text(), game = state.game, footer = {
            SharedActionButton(SharedMessages.BACK.text(), interactions::back, enabled = state.canInteract && confirmation == null,
                style = SharedButtonStyle.TEXT, modifier = observedBranch("back", observer))
            Spacer(Modifier.weight(1f))
            SharedActionButton(if (state.hasBranches) SharedMessages.HANDLE_CONFLICT.text() else SharedMessages.COMPARE_AGAIN.text(), interactions::primary,
                enabled = state.primaryEnabled && confirmation == null, modifier = observedBranch("primary", observer))
        }) {
            Text(if (state.hasBranches) SharedMessages.CHOOSE_PROGRESS.text() else SharedMessages.BRANCH_CHANGED.text(),
                fontSize = 15.sp, lineHeight = 22.5.sp)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.branches.forEach { branch ->
                    Column(Modifier.fillMaxWidth().then(observedBranch("branch:${branch.snapshotId}", observer))) {
                        ComparisonChoiceCard(branch.name, branch.summary, state.selectedId == branch.snapshotId,
                            enabled = state.canInteract && state.connected && state.hasBranches && confirmation == null,
                            onClick = { interactions.select(branch.snapshotId) })
                    }
                }
            }
            if (state.notice.isNotBlank()) Text(state.notice,
                Modifier.fillMaxWidth().padding(top = 12.dp), color = SharedUiColors.muted,
                fontSize = 13.sp, lineHeight = 19.5.sp)
            if (state.canPreserveLocal && !state.cloudOverwrite) SharedActionButton(SharedMessages.UPLOAD_KEEP_BOTH.text(), interactions::preserveLocal,
                enabled = state.preserveEnabled && confirmation == null, style = SharedButtonStyle.SECONDARY,
                modifier = Modifier.padding(top = 12.dp).then(observedBranch("preserve", observer)))
            if (state.details.isNotBlank()) {
                var expanded by remember(state.observation, state.details) { mutableStateOf(false) }
                SharedActionButton(if (expanded) SharedMessages.HIDE_DETAILS.text() else SharedMessages.DETAILS.text(), { expanded = !expanded },
                    style = SharedButtonStyle.TEXT)
                if (expanded) Text(state.details, color = SharedUiColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        confirmation?.let { SharedConfirmationDialog(it, interactions::confirm, interactions::merge, interactions::cancel) }
    }
}

private fun observedBranch(name: String, observer: SharedBranchChoiceLayoutObserver?) =
    Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
