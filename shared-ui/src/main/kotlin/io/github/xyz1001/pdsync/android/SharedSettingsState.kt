package io.github.xyz1001.pdsync.android

/** Stable identities for existing host capabilities, not a list of buttons to create automatically. */
enum class SharedSettingsKey {
    GAME, CLOUD_CONNECTION, ACCOUNT_APPRECIATION, SAVE_PATH, AUTOMATIC_UPLOAD,
    RECHECK_SAVES, REVIEW_SETUP, EXPORT_LOGS, MAINTENANCE, LINK_PROFILE, SYNC_NOTIFICATIONS,
}

/**
 * A real setting and its current display value. A missing action label is a read-only row;
 * unavailable capabilities should be omitted by the host rather than advertised by this UI.
 * The host supplies concise action labels and retains any confirmation or system chooser.
 */
data class SharedSettingsRow @JvmOverloads constructor(
    val key: SharedSettingsKey,
    val title: String,
    val currentValue: String,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = false,
    val toggleEnabled: Boolean? = null,
    val setupComplete: Boolean = false,
)

/**
 * Platform-owned settings snapshot. No connection, account, game, path or ability is invented.
 * The shared page may hide host capabilities without changing their underlying implementation.
 */
data class SharedSettingsState(
    val game: SharedGameIdentity?,
    val rows: List<SharedSettingsRow>,
    val pageActive: Boolean = false,
    val busy: Boolean = false,
    val canReturn: Boolean = true,
    val notice: String = "",
    val noticeError: Boolean = false,
) {
    init {
        require(rows.map { it.key }.distinct().size == rows.size) { "Settings row keys must be unique." }
    }
}

interface SharedSettingsActions {
    /** Dispatch to an existing host action; destructive operations still require their real confirmation. */
    fun perform(key: SharedSettingsKey)
    fun back()
}

internal val SharedSettingsRow.hasAction: Boolean get() = !actionLabel.isNullOrBlank()
/** These manual entry points are omitted on every host; automatic save checks and sync stay in the host. */
private val SharedSettingsKey.visibleInSettings: Boolean
    get() = this != SharedSettingsKey.RECHECK_SAVES && this != SharedSettingsKey.MAINTENANCE
internal val SharedSettingsState.visibleRows: List<SharedSettingsRow>
    get() = rows.filter { it.key.visibleInSettings }
internal val SharedSettingsState.canInteract: Boolean get() = pageActive && !busy
internal val SharedSettingsState.backEnabled: Boolean get() = canInteract && canReturn
internal fun SharedSettingsState.actionEnabled(key: SharedSettingsKey): Boolean =
    canInteract && key.visibleInSettings &&
        rows.singleOrNull { it.key == key }?.let { it.hasAction && it.actionEnabled } == true

/** Retained callbacks consult live capabilities and stop when the page leaves the composition. */
internal class SharedSettingsInteractions(
    private val state: () -> SharedSettingsState,
    private val actions: () -> SharedSettingsActions,
) {
    private var attached = true

    fun perform(key: SharedSettingsKey) {
        if (attached && state().actionEnabled(key)) actions().perform(key)
    }

    fun back() {
        if (attached && state().backEnabled) actions().back()
    }

    fun dispose() { attached = false }
}
