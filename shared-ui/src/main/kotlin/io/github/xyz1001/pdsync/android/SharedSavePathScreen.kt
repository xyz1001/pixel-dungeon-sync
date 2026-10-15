package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pdsync.NoviceCopy

/** One physical Step 2 screen. Adapters supply actual paths, previews and platform capabilities. */
@Composable
fun SharedSavePathScreen(
    state: SharedSavePathState,
    actions: SharedSavePathActions,
    game: SharedGameIdentity? = null,
) {
    SharedConnectionTheme {
        SharedSavePathContent(state, actions, game)
    }
}

@Composable
private fun SharedSavePathContent(
    state: SharedSavePathState,
    actions: SharedSavePathActions,
    game: SharedGameIdentity?,
) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val interactions = remember { SharedSavePathInteractions({ currentState }, { currentActions }) }
    var detailsExpanded by remember(state.path) { mutableStateOf(false) }
    val notice = NoviceCopy.feedback(state.notice, "无法读取存档，请重试。")
    val details = state.details.ifBlank { state.notice.takeIf { it != notice }.orEmpty() }
    SharedAppShell(
        title = "设置存档路径", eyebrow = "2 / 4", game = game,
        footer = {
            SharedActionButton("上一步", { interactions.perform(SharedSavePathControl.BACK) },
                enabled = state.enables(SharedSavePathControl.BACK), style = SharedButtonStyle.TEXT)
            Spacer(Modifier.weight(1f))
            SharedActionButton(state.primaryLabel, { interactions.perform(SharedSavePathControl.NEXT) },
                enabled = state.enables(SharedSavePathControl.NEXT))
        },
    ) {
        SharedForm {
            SharedTextField("存档路径", state.path, interactions::editPath,
                enabled = !state.busy, readOnly = !state.editable, singleLine = false)
            // Stack these controls so two long localized labels remain readable at 320px.
            if (state.shows(SharedSavePathControl.CHOOSE_DIRECTORY)) {
                SharedActionButton("选择其他存档路径", { interactions.perform(SharedSavePathControl.CHOOSE_DIRECTORY) },
                    enabled = state.enables(SharedSavePathControl.CHOOSE_DIRECTORY), style = SharedButtonStyle.SECONDARY)
                Spacer(Modifier.height(8.dp))
            }
            if (state.shows(SharedSavePathControl.USE_DEFAULT)) {
                SharedActionButton("使用默认路径", { interactions.perform(SharedSavePathControl.USE_DEFAULT) },
                    enabled = state.enables(SharedSavePathControl.USE_DEFAULT), style = SharedButtonStyle.TEXT)
            }
            Spacer(Modifier.height(12.dp))
            val summary = savePathSummary(state.summary)
            if (summary != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.headline)
                    summary.emptyMessage?.let { Text(it, color = SharedUiColors.muted) }
                    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        summary.slots.forEach { slot ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(slot.name, Modifier.weight(1f), color = SharedUiColors.muted,
                                    style = MaterialTheme.typography.body2)
                                Text(slot.time, color = SharedUiColors.muted,
                                    style = MaterialTheme.typography.body2, softWrap = false)
                            }
                        }
                    }
                }
            } else if (state.notice.isBlank()) {
                Text(when (state.readStatus) {
                    SharedSavePathReadStatus.READING -> "正在检查存档…"
                    SharedSavePathReadStatus.FAILED -> "无法读取存档，请重试。"
                    SharedSavePathReadStatus.PREPARE_REQUIRED -> "下一步将创建此存档目录。"
                    else -> "选择存档路径后检查存档。"
                }, color = SharedUiColors.muted)
            }
            if (state.notice.isNotBlank()) {
                Text(notice, color = if (state.readStatus == SharedSavePathReadStatus.FAILED)
                    MaterialTheme.colors.error else SharedUiColors.muted)
            }
            if (state.shows(SharedSavePathControl.INITIALIZE_GAME)) {
                Spacer(Modifier.height(12.dp))
                SharedActionButton("启动游戏一次", { interactions.perform(SharedSavePathControl.INITIALIZE_GAME) },
                    enabled = state.enables(SharedSavePathControl.INITIALIZE_GAME), style = SharedButtonStyle.SECONDARY)
            }
            if (details.isNotBlank()) {
                SharedActionButton(if (detailsExpanded) "收起详细信息" else "详细信息",
                    { detailsExpanded = !detailsExpanded }, style = SharedButtonStyle.TEXT)
                if (detailsExpanded) {
                    Text(details, style = MaterialTheme.typography.caption, color = SharedUiColors.muted)
                    if (state.shows(SharedSavePathControl.COPY_DIAGNOSTICS)) {
                        SharedActionButton("复制诊断", { interactions.perform(SharedSavePathControl.COPY_DIAGNOSTICS) },
                            enabled = state.enables(SharedSavePathControl.COPY_DIAGNOSTICS), style = SharedButtonStyle.TEXT)
                    }
                }
            }
            if (state.busy || state.readStatus == SharedSavePathReadStatus.READING) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
