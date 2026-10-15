package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pdsync.NoviceCopy
import pdsync.OnboardingKind

/** One physical Step 4 screen. The optional appreciation slot never changes comparison actions. */
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
    SharedAppShell(title = "选择存档", eyebrow = "4 / 4", game = state.game, onboardingStep = 4,
        footer = {
            if (state.showsBack) SharedActionButton("上一步", interactions::back,
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.offers(SharedProgressChoice.LOCAL)) ComparisonChoiceCard(
                        title = "使用本机进度", summary = listOf(state.localLabel, state.localDetails)
                            .filter(String::isNotBlank).joinToString(" · "),
                        selected = state.selectedProgress == SharedProgressChoice.LOCAL,
                        enabled = state.canInteract && state.configured && state.activeConfirmation == null,
                        onClick = { interactions.selectProgress(SharedProgressChoice.LOCAL) },
                    )
                    if (state.offers(SharedProgressChoice.CLOUD)) ComparisonChoiceCard(
                        title = "使用云端进度", summary = state.remoteChoices.singleOrNull()?.let {
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
                Text("选择云端存档", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = if (selected) Color(0xFFF0F6F1) else MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colors.primary else SharedUiColors.line)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadioButton(selected, onClick = null, enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colors.primary,
                    unselectedColor = SharedUiColors.muted))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, lineHeight = 19.2.sp, color = SharedUiColors.muted)
            }
        }
    }
}

@Composable
private fun ComparisonNotice(state: SharedComparisonState) {
    val notice = state.notice.takeIf(String::isNotBlank)?.let {
        NoviceCopy.feedback(it, if (state.feedbackError) "操作未完成，请重试。" else "请查看详细信息。")
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
    SharedActionButton(if (expanded) "收起详细信息" else "详细信息", { expanded = !expanded },
        style = SharedButtonStyle.TEXT)
    if (expanded) {
        Text(text, fontSize = 12.sp, lineHeight = 19.2.sp, color = SharedUiColors.muted)
        if (state.canCopyDiagnostics && state.details.isNotBlank()) SharedActionButton("复制诊断", interactions::copyDiagnostics,
            style = SharedButtonStyle.TEXT, enabled = state.canInteract)
    }
}
