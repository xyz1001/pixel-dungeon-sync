package pdsync

data class SyncHistory(val graph: CommitGraph, val baseline: String?, val currentFiles: Map<String,String>, val currentName: String,
 val summary: SaveSummary?, val aliases: Map<String,String>, val baselineDirection:String?=null,
 val verifiedCommitIds:Set<String> = if(graph.verification == RemoteVerification.OBJECTS_VERIFIED) graph.commits.keys.toSet() else emptySet(),
 internal val owner:Any?=null, val displayAnchor:String?=null, val simple:SimpleHistory?=null) {
 val baselineCommit get()=baseline?.takeIf { it in verifiedCommitIds }?.let { graph.commits[it] }
 val clean get()=simple?.let {it.currentId==it.latest} ?: (currentFiles==baselineCommit?.snapshot?.files)
 /** Display-only remembered association; never used as upload/restore authorization. */
 val displayBaselineCommit get()=baselineCommit ?: displayAnchor?.let(graph.commits::get)
 val localNodeCount get()=if(currentFiles.isEmpty())0 else if(displayBaselineCommit==null || currentFiles==displayBaselineCommit!!.snapshot.files || displayBaselineCommit!!.snapshot.files.isEmpty())1 else 2
 /** Only an independently verified cloud copy proves current content is protected. */
 val currentProtected get()=simple?.let {it.currentId==it.latest} ?: (currentFiles.isEmpty() || graph.commits.values.any { it.id in verifiedCommitIds && it.snapshot.files==currentFiles })
 val cloud get()=simple?.nodes?.map {node->AppendCommit(id=node.hashId,parents=listOfNotNull(node.parentId),snapshot=Manifest(game="",profile="",version="",id=node.hashId,parent=node.parentId,sha256="",files=emptyMap()),objectBytes=0)} ?: graph.commits.values.snapshotRepresentatives(graph.tips).sortedWith(compareByDescending<AppendCommit> { it.snapshot.metadata?.latestFileModifiedAt ?: it.snapshot.metadata?.capturedAt ?: it.operation?.preparedAt.orEmpty() }.thenBy { it.id })
 val defaultSnapshot get()=simple?.latest ?: graph.tips.singleOrNull()?.let { graph.commits.getValue(it).snapshot.id }
}
data class SimpleHistory(val nodes:List<SaveNode>,val latest:String?,val currentId:String?)
