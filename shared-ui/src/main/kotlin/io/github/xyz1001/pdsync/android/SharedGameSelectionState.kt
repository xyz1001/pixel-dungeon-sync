package io.github.xyz1001.pdsync.android

/** Existing platform operations; the shared page never opens a picker or installs an application. */
enum class SharedGameSelectionControl {
    CHOOSE_FILE, CHOOSE_INSTALLED, CHOOSE_ROOT, SWITCH_TO_EMBEDDED, CHECK_ACCESS, LAUNCH_GAME,
    ACTIVATE_BRIDGE, INITIALIZE_GAME, OPEN_RECOVERY, CONTINUE_INSTALLATION, OPEN_SYSTEM_CONFIRMATION,
    CANCEL_INSTALLATION, CHECK_EXPIRED_REQUEST, CLEAN_LEGACY_REQUEST, OPEN_INSTALL_SETTINGS,
    RESCAN, COPY_DIAGNOSTICS, CANCEL_ACCESS_SELECTION, USE_OTHER_INSTALLER, CHECK_EXTERNAL_INSTALL,
}

data class SharedGameSelectionRow(
    val control: SharedGameSelectionControl,
    val label: String,
    val enabled: Boolean = true,
)

data class SharedGameCandidate(val id: String, val game: SharedGameIdentity, val location: String)

enum class SharedGameAccessMode { NONE, ROOT, APK }
enum class SharedGameAccessStatus { UNCHECKED, WORKING, READY, FAILED }

/** Display-only observations supplied by Android. They never grant access or enable Next. */
data class SharedGameAccessState(
    val mode: SharedGameAccessMode,
    val status: SharedGameAccessStatus,
)

/** A platform-owned observation. A selected identity alone never grants permission to continue. */
data class SharedGameSelectionState @JvmOverloads constructor(
    val game: SharedGameIdentity? = null,
    val selectionId: String? = null,
    val selectionStatus: String = "",
    val location: String = "",
    val rows: List<SharedGameSelectionRow> = emptyList(),
    val candidates: List<SharedGameCandidate> = emptyList(),
    val pageActive: Boolean = false,
    val busy: Boolean = false,
    val canContinue: Boolean = false,
    val notice: String = "",
    val noticeError: Boolean = false,
    val warning: String = "",
    val details: String = "",
    val canCopyDiagnostics: Boolean = false,
    val access: SharedGameAccessState? = null,
) {
    init {
        require(rows.map { it.control }.distinct().size == rows.size) { "Game action keys must be unique." }
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Game candidate keys must be unique." }
    }

    val canInteract: Boolean get() = pageActive && !busy
    val nextEnabled: Boolean get() = canInteract && canContinue && game != null && !selectionId.isNullOrBlank()
    fun actionEnabled(control: SharedGameSelectionControl): Boolean = canInteract &&
        if (control == SharedGameSelectionControl.COPY_DIAGNOSTICS) canCopyDiagnostics
        else rows.singleOrNull { it.control == control }?.enabled == true
}

interface SharedGameSelectionActions {
    fun perform(control: SharedGameSelectionControl)
    fun chooseCandidate(id: String)
    fun next()
}

/** Live capability checks also protect callbacks retained during rerender, dismissal and navigation. */
internal class SharedGameSelectionInteractions(
    private val state: () -> SharedGameSelectionState,
    private val actions: () -> SharedGameSelectionActions,
) {
    private var attached = true
    private var claimedNext: SharedGameSelectionState? = null

    fun perform(control: SharedGameSelectionControl) {
        if (attached && state().actionEnabled(control)) actions().perform(control)
    }

    fun chooseCandidate(id: String) {
        val current = state()
        if (attached && current.canInteract && current.candidates.any { it.id == id }) actions().chooseCandidate(id)
    }

    fun next() {
        val current = state()
        if (!attached || !current.nextEnabled || claimedNext === current) return
        // Consume this rendered observation before invoking the host. A fresh failed/retried
        // observation can re-enable Next; rapid repeated clicks cannot dispatch the same one twice.
        claimedNext = current
        actions().next()
    }

    fun dispose() { attached = false }
}
