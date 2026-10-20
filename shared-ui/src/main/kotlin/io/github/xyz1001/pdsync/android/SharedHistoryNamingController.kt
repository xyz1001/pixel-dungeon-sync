package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.function.Consumer

/**
 * One rendered home's draft. Opening and typing do not replace the home observation or perform
 * I/O. The activity's existing captured HomeActionContext still authorizes every save/maintenance.
 */
class SharedHistoryNamingController {
    var naming by mutableStateOf<SharedHistoryNaming?>(null)
        private set
    private var active = true
    private var nextToken = 0L

    fun open(snapshotId: String?, initialName: String, notice: String,
             save: Consumer<String>, maintenance: Runnable?) {
        if (!active) return
        val token = ++nextToken
        naming = SharedHistoryNaming(snapshotId, SharedNamingState(token, initialName, notice = notice),
            onNameChange = { value ->
                current(token)?.let { naming = it.copy(state = it.state.copy(name = value)) }
            },
            onSave = { value -> if (take(token)) save.accept(value) },
            onDismiss = { take(token) },
            onMaintenance = maintenance?.let { action -> { if (take(token)) action.run() } })
    }

    private fun current(token: Long): SharedHistoryNaming? =
        naming?.takeIf { active && it.state.token == token }

    private fun take(token: Long): Boolean {
        if (current(token) == null) return false
        naming = null
        return true
    }

    /** Retained callbacks from an old render or foreground session can never affect a new one. */
    fun close() {
        active = false
        naming = null
    }
}
