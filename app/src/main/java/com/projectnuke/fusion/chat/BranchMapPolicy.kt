package com.projectnuke.fusion.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BranchRecord(val id: Long, val parentId: Long?, val title: String, val updatedAt: Long, val archived: Boolean = false)
internal data class BranchMapNode(val id: Long, val title: String, val depth: Int, val current: Boolean)

internal object BranchMapPolicy {
    const val MAX_NODES = 40
    const val MAX_ANCESTORS = 8
    const val MAX_DESCENDANT_DEPTH = 3
    suspend fun load(currentId: Long, includeArchived: Boolean, lookup: suspend (Long) -> BranchRecord?,
        children: suspend (Long,Int) -> List<BranchRecord>): List<BranchMapNode> {
        fun accessible(record: BranchRecord?) = record?.takeIf { includeArchived || !it.archived }
        val current = accessible(lookup(currentId)) ?: return emptyList()
        val chain=mutableListOf(current); val records=linkedMapOf(current.id to current)
        for (step in 0 until MAX_ANCESTORS) {
            currentCoroutineContext().ensureActive()
            val parentId=chain.last().parentId ?: break
            if (parentId in records) break
            val parent=accessible(lookup(parentId)) ?: break
            records[parent.id]=parent; chain+=parent
        }
        val queue=java.util.ArrayDeque<Pair<Long,Int>>()
        chain.forEach { queue.add(it.id to 0) }
        val expanded=mutableSetOf<Long>()
        while (queue.isNotEmpty() && records.size < MAX_NODES) {
            currentCoroutineContext().ensureActive()
            val (id,depth)=queue.removeFirst()
            if (depth >= MAX_DESCENDANT_DEPTH || !expanded.add(id)) continue
            val limit=minOf(32,MAX_NODES-records.size)
            children(id,limit).asSequence().take(limit).filter { it.parentId == id && accessible(it) != null }.forEach {
                if (records.putIfAbsent(it.id,it) == null) queue.add(it.id to depth+1)
            }
        }
        val byParent=records.values.groupBy { it.parentId }
        val path=chain.map { it.id }.toSet(); val seen=mutableSetOf<Long>(); val output=mutableListOf<BranchMapNode>()
        fun visit(record: BranchRecord,depth:Int) {
            if (!seen.add(record.id) || output.size>=MAX_NODES) return
            output+=BranchMapNode(record.id,record.title.take(160),depth.coerceAtMost(4),record.id==currentId)
            byParent[record.id].orEmpty().sortedWith(compareBy<BranchRecord> { if (it.id in path) 0 else 1 }
                .thenByDescending { it.updatedAt }.thenBy { it.id }).forEach { visit(it,depth+1) }
        }
        visit(chain.last(),0)
        return output
    }
}
