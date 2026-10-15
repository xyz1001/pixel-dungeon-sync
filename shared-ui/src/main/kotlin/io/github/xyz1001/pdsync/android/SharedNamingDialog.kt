package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/** A draft only. Saving remains an explicit host action; dismissal never writes an alias. */
data class SharedNamingState(
    val token: Long,
    val name: String,
    val busy: Boolean = false,
    val title: String = "存档名称",
    val notice: String = "",
)

internal class SharedNamingInteractions(
    private val state: () -> SharedNamingState?,
    private val onNameChange: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var active = true

    fun dispose() { active = false }

    fun edit(token: Long, name: String) {
        if (!active) return
        val current = state() ?: return
        if (current.token == token && !current.busy) onNameChange(name)
    }
    fun save(token: Long) {
        if (!active) return
        val current = state() ?: return
        if (current.token == token && !current.busy) onSave(current.name)
    }
    fun dismiss(token: Long) {
        if (!active) return
        val current = state() ?: return
        if (current.token == token && !current.busy) onDismiss()
    }
}

@Composable
fun SharedNamingDialog(
    state: SharedNamingState,
    onNameChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    maintenance: @Composable ColumnScope.() -> Unit = {},
) {
    val interactions = namingInteractions(state, onNameChange, onSave, onDismiss)
    SharedConnectionTheme {
        SharedConfirmationDialog(state.presentation, interactions::save, onDismiss = interactions::dismiss) {
            NamingFields(state, interactions, maintenance)
        }
    }
}

/** Used by rendered tests; production and tests compile exactly the same fields and actions. */
@Composable
internal fun SharedNamingDialogBody(
    state: SharedNamingState,
    onNameChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maintenance: @Composable ColumnScope.() -> Unit = {},
) {
    val interactions = namingInteractions(state, onNameChange, onSave, onDismiss)
    SharedConfirmationDialogBody(state.presentation, interactions::save, onDismiss = interactions::dismiss,
        modifier = modifier) { NamingFields(state, interactions, maintenance) }
}

private val SharedNamingState.presentation: SharedConfirmationState
    get() = SharedConfirmationState(token, title, "", "保存", confirmEnabled = true, busy = busy)

@Composable
private fun namingInteractions(state: SharedNamingState, onNameChange: (String) -> Unit,
                               onSave: (String) -> Unit, onDismiss: () -> Unit): SharedNamingInteractions {
    val currentState by rememberUpdatedState(state)
    val currentChange by rememberUpdatedState(onNameChange)
    val currentSave by rememberUpdatedState(onSave)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val interactions = remember { SharedNamingInteractions({ currentState }, { currentChange(it) },
        { currentSave(it) }, { currentDismiss() }) }
    DisposableEffect(interactions) { onDispose { interactions.dispose() } }
    return interactions
}

@Composable
private fun ColumnScope.NamingFields(state: SharedNamingState, interactions: SharedNamingInteractions,
                                    maintenance: @Composable ColumnScope.() -> Unit) {
    SharedTextField("存档名称", state.name, { interactions.edit(state.token, it) }, enabled = !state.busy)
    Text("留空使用默认名，最多40字", color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
    if (state.notice.isNotBlank()) Text(state.notice, color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
    maintenance()
}
