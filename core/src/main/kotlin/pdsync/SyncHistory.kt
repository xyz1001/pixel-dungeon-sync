package pdsync

data class SyncHistory(val graph: CommitGraph, val baseline: String?, val currentFiles: Map<String,String>, val currentName: String,
 val summary: SaveSummary?, val aliases: Map<String,String>, val baselineDirection:String?=null) {
 val baselineCommit get()=baseline?.let { graph.commits[it] }
 val clean get()=currentFiles==baselineCommit?.snapshot?.files
 val localNodeCount get()=if(currentFiles.isEmpty())0 else if(clean || baselineCommit==null || baselineCommit!!.snapshot.files.isEmpty())1 else 2
 /** Only an independently verified cloud copy proves current content is protected. */
 val currentProtected get()=currentFiles.isEmpty() || graph.commits.values.any { it.snapshot.files==currentFiles }
 val cloud get()=graph.commits.values.snapshotRepresentatives(graph.tips).sortedWith(compareByDescending<AppendCommit> { it.snapshot.metadata?.latestFileModifiedAt ?: it.snapshot.metadata?.capturedAt ?: it.operation?.preparedAt.orEmpty() }.thenBy { it.id })
 val defaultSnapshot get()=graph.tips.singleOrNull()?.let { graph.commits.getValue(it).snapshot.id }
}
