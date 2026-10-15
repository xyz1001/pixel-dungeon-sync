package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Measures the real production layout without a second test-only rendering implementation. */
internal interface SharedGameSelectionLayoutObserver {
    fun element(name: String, bounds: Rect) {}
    fun text(name: String, lineCount: Int, overflow: Boolean) {}
}
internal val LocalSharedGameSelectionLayoutObserver = staticCompositionLocalOf<SharedGameSelectionLayoutObserver?> { null }

/** One Step 1 layout. Hosts supply real identities, platform actions and prerequisite checks. */
@Composable
fun SharedGameSelectionScreen(state: SharedGameSelectionState, actions: SharedGameSelectionActions) {
    SharedConnectionTheme {
        val currentState by rememberUpdatedState(state)
        val currentActions by rememberUpdatedState(actions)
        val interactions = remember { SharedGameSelectionInteractions({ currentState }, { currentActions }) }
        DisposableEffect(interactions) { onDispose { interactions.dispose() } }
        val observer = LocalSharedGameSelectionLayoutObserver.current
        fun measured(name: String) = Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
        SharedAppShell(title = "选择游戏", eyebrow = "1 / 3", footer = {
            Spacer(Modifier.weight(1f))
            SharedActionButton("下一步", interactions::next, measured("next"), enabled = state.nextEnabled)
        }) {
            Surface(Modifier.fillMaxWidth().then(measured("identity")), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SharedUiColors.line), elevation = 0.dp) {
                BoxWithConstraints(Modifier.padding(18.dp)) {
                    val compact = maxWidth <= 360.dp
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(Modifier.size(58.dp), color = SharedUiColors.secondary, shape = RoundedCornerShape(14.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (state.game != null && state.game.icon != null) {
                                        Image(state.game.icon, contentDescription = null,
                                            modifier = Modifier.size(48.dp).then(measured("game-icon")),
                                            contentScale = ContentScale.Fit)
                                    } else {
                                        Text(state.game?.name?.firstOrNull()?.toString() ?: "?", fontSize = 28.sp,
                                            modifier = measured("game-icon-fallback"), color = MaterialTheme.colors.primary)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(state.game?.name ?: "尚未选择游戏", fontWeight = FontWeight.SemiBold,
                                    modifier = measured("game-name"), onTextLayout = {
                                        observer?.text("game-name", it.lineCount, it.hasVisualOverflow)
                                    })
                                state.game?.version?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = SharedUiColors.muted, fontSize = 13.sp)
                                }
                            }
                            if (!compact && state.selectionStatus.isNotBlank()) SelectionPill(state.selectionStatus)
                        }
                        if (compact && state.selectionStatus.isNotBlank()) SelectionPill(state.selectionStatus)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            state.candidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth().then(measured("candidate:${candidate.id}"))
                    .clickable(enabled = state.canInteract) { interactions.chooseCandidate(candidate.id) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.selectionId == candidate.id, onClick = null, enabled = state.canInteract)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(candidate.game.summary)
                        Text(candidate.location, fontSize = 12.sp, color = SharedUiColors.muted)
                    }
                }
            }
            SharedForm {
                state.rows.forEach { row ->
                    SharedActionButton(row.label, { interactions.perform(row.control) },
                        Modifier.fillMaxWidth().then(measured("action:${row.control.name}")),
                        enabled = state.actionEnabled(row.control), style = SharedButtonStyle.SECONDARY)
                    Spacer(Modifier.height(8.dp))
                }
                if (state.warning.isNotBlank()) {
                    Text(state.warning, color = MaterialTheme.colors.error, modifier = measured("warning"))
                    Spacer(Modifier.height(12.dp))
                }
                if (state.notice.isNotBlank()) {
                    Text(state.notice, color = if (state.noticeError) MaterialTheme.colors.error else SharedUiColors.muted,
                        modifier = measured("notice"))
                    Spacer(Modifier.height(8.dp))
                }
                if (state.busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SelectionPill(label: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = SharedUiColors.secondary) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MaterialTheme.colors.primary, fontSize = 12.sp)
    }
}
