package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pdsync.NoviceCopy
import pdsync.OnboardingKind

/** Retained comparison/recovery component. The ordinary setup flow has only two steps. */
@Composable
fun SharedComparisonScreen(
    state: SharedComparisonState,
    actions: SharedComparisonActions,
    appreciation: (@Composable () -> Unit)? = null,
) {
    SharedConnectionTheme { SharedComparisonContent(state, actions, appreciation) }
}

@Composable
private fun SharedComparisonContent(
    state: SharedComparisonState,
    actions: SharedComparisonActions,
    appreciation: (@Composable () -> Unit)?,
) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedComparisonInteractions({ currentState }, { currentActions }) }
    SharedAppShell(title = SharedMessages.CHOOSE_SAVE.text(), eyebrow = "4 / 4", game = state.game, onboardingStep = 4,
        footer = {
            if (state.showsBack) SharedActionButton(SharedMessages.BACK_STEP.text(), interactions::back,
                enabled = state.canInteract && state.activeConfirmation == null, style = SharedButtonStyle.TEXT)
            Spacer(Modifier.weight(1f))
            SharedActionButton(state.primaryLabel, { interactions.performPrimary(state.primary) }, enabled = state.primaryEnabled)
        }) {
        if (state.isSimpleCompletion) {
            Column(Modifier.fillMaxWidth().heightIn(min = if (appreciation == null) 285.dp else 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(state.headline, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight(650))
                if (state.kind != OnboardingKind.IDENTICAL || state.feedbackError) ComparisonNotice(state)
            }
        } else {
            val hasChoices = state.offers(SharedProgressChoice.LOCAL) || state.offers(SharedProgressChoice.CLOUD)
            if (hasChoices) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.offers(SharedProgressChoice.LOCAL)) ComparisonChoiceCard(
                        title = SharedMessages.USE_LOCAL.text(), summary = listOf(state.localLabel, state.localDetails)
                            .filter(String::isNotBlank).joinToString(" · "),
                        selected = state.selectedProgress == SharedProgressChoice.LOCAL,
                        enabled = state.canInteract && state.configured && state.activeConfirmation == null,
                        onClick = { interactions.selectProgress(SharedProgressChoice.LOCAL) },
                    )
                    if (state.offers(SharedProgressChoice.CLOUD)) ComparisonChoiceCard(
                        title = SharedMessages.USE_CLOUD.text(), summary = state.remoteChoices.singleOrNull()?.let {
                            listOf(it.label, it.summary).filter(String::isNotBlank).joinToString(" · ")
                        }.orEmpty(),
                        selected = state.selectedProgress == SharedProgressChoice.CLOUD,
                        enabled = state.canInteract && state.configured && state.activeConfirmation == null,
                        onClick = { interactions.selectProgress(SharedProgressChoice.CLOUD) },
                    )
                }
            } else {
                Text(state.headline, color = if (state.primary == SharedComparisonPrimary.REFRESH) SharedUiColors.muted
                    else MaterialTheme.colors.onSurface, fontSize = 16.sp, lineHeight = 25.6.sp)
            }
            if (!hasChoices || state.feedbackError) ComparisonNotice(state)
            if (state.primary in setOf(SharedComparisonPrimary.REFRESH, SharedComparisonPrimary.RETRY_UPLOAD,
                    SharedComparisonPrimary.OPEN_RECOVERY)) ComparisonDetails(state, interactions)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        appreciation?.let { content ->
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) { content() }
        }
    }
    state.confirmationPresentation?.let { presentation ->
        SharedConfirmationDialog(presentation, interactions::confirm, interactions::previewMerge, interactions::cancel) {
            if (state.activeConfirmation?.stage == SharedComparisonConfirmationStage.CONFLICT && (state.remoteChoices.size > 1 || !state.hasSelectedRemote)) {
                Text(SharedMessages.CHOOSE_CLOUD_SAVE.text(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                state.remoteChoices.forEach { choice ->
                    ComparisonChoiceCard(choice.label, choice.summary, state.selectedId == choice.snapshotId,
                        enabled = state.canInteract && state.configured,
                        onClick = { interactions.selectRemote(choice.snapshotId) })
                }
            }
        }
    }
}

@Composable
internal fun ComparisonChoiceCard(title: String, summary: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // M3 .choice tokens; selection remains explicit through the real radio and teal outline.
    // Hover only changes presentation and never grants an otherwise disabled capability.
    Surface(Modifier.fillMaxWidth().clickable(interactionSource = interactionSource,
        indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled && hovered) SharedUiColors.background else MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, if (selected || enabled && hovered) MaterialTheme.colors.primary else SharedUiColors.line)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(18.dp),
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colors.primary,
                    unselectedColor = SharedUiColors.muted))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold)
                if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, lineHeight = 18.sp, color = SharedUiColors.muted)
            }
        }
    }
}

@Composable
private fun ComparisonNotice(state: SharedComparisonState) {
    val notice = state.notice.takeIf(String::isNotBlank)?.let {
        NoviceCopy.feedback(it, if (state.feedbackError) SharedMessages.ACTION_INCOMPLETE.text() else SharedMessages.SEE_DETAILS.text())
    }
    if (notice != null && notice != state.headline) Text(notice, Modifier.padding(top = 12.dp),
        color = if (state.feedbackError) MaterialTheme.colors.error else SharedUiColors.muted,
        fontSize = 14.sp, lineHeight = 22.4.sp)
}

@Composable
private fun ComparisonDetails(state: SharedComparisonState, interactions: SharedComparisonInteractions) {
    var expanded by remember(state.kind, state.details, state.localDetails) { mutableStateOf(false) }
    val text = listOf(state.localDetails,
        state.remoteChoices.joinToString("\n") { listOf(it.label, it.summary).filter(String::isNotBlank).joinToString(" · ") },
        state.details).filter(String::isNotBlank).distinct().joinToString("\n\n")
    if (text.isBlank()) return
    SharedActionButton(if (expanded) SharedMessages.HIDE_DETAILS.text() else SharedMessages.DETAILS.text(), { expanded = !expanded },
        style = SharedButtonStyle.TEXT)
    if (expanded) {
        Text(text, fontSize = 12.sp, lineHeight = 19.2.sp, color = SharedUiColors.muted)
        if (state.canCopyDiagnostics && state.details.isNotBlank()) SharedActionButton(SharedMessages.COPY_DIAGNOSTICS.text(), interactions::copyDiagnostics,
            style = SharedButtonStyle.TEXT, enabled = state.canInteract)
    }
}
