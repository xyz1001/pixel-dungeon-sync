package io.github.xyz1001.pdsync.android
import androidx.activity.ComponentActivity
class SharedGameIdentity(val name: String, val version: String)
class SharedGameCandidate(val id: String, val game: SharedGameIdentity, val location: String)
object AndroidGameIcon { fun load(activity: ComponentActivity, id: String, source: String, other: Any?): Any? = null }
object PickerUi {
    var draws = 0
    lateinit var choose: (String) -> Unit
    lateinit var dismiss: () -> Unit
    var file: (() -> Unit)? = null
    lateinit var search: (String) -> Unit
    lateinit var all: (Boolean) -> Unit
    var candidates = emptyList<SharedGameCandidate>()
    var noMatches = false
}
fun SharedGamePickerDialog(candidates: List<SharedGameCandidate>, selectedId: String?, enabled: Boolean,
    onChoose: (String) -> Unit, onDismiss: () -> Unit, fileLabel: String?, fileDescription: String,
    onChooseFile: (() -> Unit)?, searchQuery: String?, onSearch: (String) -> Unit,
    showAll: Boolean, onShowAll: (Boolean) -> Unit, noSearchMatches: Boolean,
    candidateIcon: (String) -> Any?) {
    PickerUi.draws++; PickerUi.choose = onChoose; PickerUi.dismiss = onDismiss; PickerUi.file = onChooseFile
    PickerUi.search = onSearch; PickerUi.all = onShowAll; PickerUi.candidates = candidates; PickerUi.noMatches = noSearchMatches
}
