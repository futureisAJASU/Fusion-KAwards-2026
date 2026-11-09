package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.BranchMapNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BranchMapState(val nodes: List<BranchMapNode> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)
internal interface BranchMapSource {
    suspend fun load(id: Long): List<BranchMapNode>
    suspend fun accessible(id: Long): Boolean
    suspend fun synthesisSources(
        ids: List<Long>,
        intent: com.projectnuke.fusion.chat.BranchSynthesisIntent,
    ): com.projectnuke.fusion.chat.BranchSynthesisContextLoadResult =
        com.projectnuke.fusion.chat.BranchSynthesisContextLoadResult.InvalidSelection
}
internal class BranchMapController(private val scope: CoroutineScope, private val source: BranchMapSource) {
    private val mutableState=MutableStateFlow(BranchMapState())
    val state=mutableState.asStateFlow()
    private var job: Job?=null
    private var revision=0L
    fun load(id:Long) {
        job?.cancel(); val run=++revision; mutableState.value=BranchMapState()
        job=scope.launch {
            try { val nodes=source.load(id); currentCoroutineContext().ensureActive()
                if (run==revision) mutableState.value=BranchMapState(nodes,loading=false)
            } catch (e:CancellationException) { throw e }
            catch (_:Exception) { if (run==revision) mutableState.value=BranchMapState(loading=false,failed=true) }
        }
    }
    fun close() { revision++; job?.cancel(); mutableState.value=BranchMapState(loading=false) }
}
