package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A draft only. Saving remains an explicit host action; dismissal never writes an alias. */
data class SharedNamingState(
    val token: Long,
    val name: String,
    val busy: Boolean = false,
    val title: String = SharedMessages.SAVE_NAME.text(),
    val notice: String = "",
)

/**
 * A host-owned inline draft. The snapshot ID is the real history ID, or null for the current
 * directory. No alias, metadata, or success state is changed by this presentation.
 * Identity maintenance remains a separate explicit action with the host's original proof checks.
 */
data class SharedHistoryNaming(
    val snapshotId: String?,
    val state: SharedNamingState,
    val onNameChange: (String) -> Unit,
    val onSave: (String) -> Unit,
    val onDismiss: () -> Unit,
    val onMaintenance: (() -> Unit)? = null,
)

internal interface SharedNamingLayoutObserver {
    fun element(name: String, boundsInRoot: Rect) {}
}
internal val LocalSharedNamingLayoutObserver = staticCompositionLocalOf<SharedNamingLayoutObserver?> { null }

internal class SharedNamingInteractions(
    private val state: () -> SharedNamingState?,
    private val onNameChange: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onDismiss: () -> Unit,
    private val onMaintenance: (() -> Unit)? = null,
) {
    private var active = true

    fun dispose() { active = false }

    fun edit(token: Long, name: String) {
        if (!active) return
        val current = state() ?: return
        if (current.token == token && !current.busy) onNameChange(boundedSnapshotNameDraft(name))
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
    fun maintenance(token: Long) {
        if (!active) return
        val current = state() ?: return
        if (current.token == token && !current.busy) onMaintenance?.invoke()
    }
}

/** Limit the draft without splitting a supplementary Unicode character or changing its spaces. */
internal fun boundedSnapshotNameDraft(name: String): String {
    val count = name.codePointCount(0, name.length)
    return if (count <= 40) name else name.substring(0, name.offsetByCodePoints(0, 40))
}

/** The reference's compact editor lives below its node, so history remains in view. */
@Composable
fun SharedInlineNaming(naming: SharedHistoryNaming, modifier: Modifier = Modifier) {
    // A new host request gets new field/button nodes as well as a new token. Material may update
    // an existing node's retained semantics callback in place; do not let that cross draft owners.
    key(naming.state.token) { SharedInlineNamingBody(naming, modifier) }
}

@Composable
private fun SharedInlineNamingBody(naming: SharedHistoryNaming, modifier: Modifier) {
    val state = naming.state
    val interactions = namingInteractions(state, naming.onNameChange, naming.onSave,
        naming.onDismiss, naming.onMaintenance)
    val observer = LocalSharedNamingLayoutObserver.current
    fun observed(name: String) = Modifier.onGloballyPositioned { observer?.element(name, it.boundsInRoot()) }
    val focus = remember(state.token) { FocusRequester() }
    LaunchedEffect(state.token) { focus.requestFocus() }
    Column(modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(state.name, { interactions.edit(state.token, it) }, enabled = !state.busy,
            singleLine = true,
            textStyle = TextStyle(color = SharedUiColors.text, fontSize = 12.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { interactions.save(state.token) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        interactions.dismiss(state.token); true
                    } else false
                }
                .semantics { contentDescription = SharedMessages.SAVE_NAME.text() }
                .border(1.dp, MaterialTheme.colors.primary, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp).then(observed("name")),
            decorationBox = { field ->
                Box {
                    if (state.name.isEmpty()) Text(SharedMessages.NAME_HINT.text(), color = SharedUiColors.muted,
                        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
                    field()
                }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InlineNamingButton(if (state.busy) SharedMessages.SAVING.text() else SharedMessages.SAVE.text(), { interactions.save(state.token) },
                enabled = !state.busy, primary = true, modifier = observed("save"))
            InlineNamingButton(SharedMessages.CANCEL.text(), { interactions.dismiss(state.token) },
                enabled = !state.busy, primary = false, modifier = observed("cancel"))
        }
        if (state.notice.isNotBlank()) Text(state.notice, color = SharedUiColors.muted,
            fontSize = 11.sp, lineHeight = 16.sp)
        if (naming.onMaintenance != null) TextButton(onClick = { interactions.maintenance(state.token) },
            enabled = !state.busy, modifier = Modifier.height(28.dp).then(observed("maintenance")),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
            Text(SharedMessages.CONFIRM_RUN_IDENTITY.text(), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun InlineNamingButton(label: String, onClick: () -> Unit, enabled: Boolean,
                               primary: Boolean, modifier: Modifier) {
    Button(onClick, modifier.height(28.dp).defaultMinSize(minWidth = 1.dp), enabled = enabled,
        shape = RoundedCornerShape(6.dp), elevation = null,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (primary) MaterialTheme.colors.primary else SharedUiColors.secondary,
            contentColor = if (primary) Color.White else SharedUiColors.text)) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
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
    get() = SharedConfirmationState(token, title, "", SharedMessages.SAVE.text(), confirmEnabled = true, busy = busy)

@Composable
private fun namingInteractions(state: SharedNamingState, onNameChange: (String) -> Unit,
                               onSave: (String) -> Unit, onDismiss: () -> Unit,
                               onMaintenance: (() -> Unit)? = null): SharedNamingInteractions {
    val currentState by rememberUpdatedState(state)
    val currentChange by rememberUpdatedState(onNameChange)
    val currentSave by rememberUpdatedState(onSave)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentMaintenance by rememberUpdatedState(onMaintenance)
    val interactions = remember { SharedNamingInteractions({ currentState }, { currentChange(it) },
        { currentSave(it) }, { currentDismiss() }, { currentMaintenance?.invoke() }) }
    DisposableEffect(interactions) { onDispose { interactions.dispose() } }
    return interactions
}

@Composable
private fun ColumnScope.NamingFields(state: SharedNamingState, interactions: SharedNamingInteractions,
                                    maintenance: @Composable ColumnScope.() -> Unit) {
    SharedTextField(SharedMessages.SAVE_NAME.text(), state.name, { interactions.edit(state.token, it) }, enabled = !state.busy)
    Text(SharedMessages.NAME_HINT.text(), color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
    if (state.notice.isNotBlank()) Text(state.notice, color = SharedUiColors.muted, fontSize = 13.sp, lineHeight = 20.sp)
    maintenance()
}
