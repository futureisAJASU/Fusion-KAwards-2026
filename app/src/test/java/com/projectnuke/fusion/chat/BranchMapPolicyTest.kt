package com.projectnuke.fusion.chat

import com.projectnuke.fusion.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class BranchMapPolicyTest {
    private suspend fun load(rows:List<BranchRecord>,id:Long=2,archive:Boolean=false):List<BranchMapNode> =
        BranchMapPolicy.load(id,archive,{ key -> rows.firstOrNull { it.id==key } },{ parent,limit -> rows.filter { it.parentId==parent }.take(limit) })
    private val parent=BranchRecord(1,null,"parent",0)
    private val current=BranchRecord(2,1,"current",1)
    @Test fun parentCurrentAndChildrenHaveStableHierarchy()=runBlocking {
        val rows=listOf(parent,current,BranchRecord(3,2,"child",3),BranchRecord(4,1,"sibling",2))
        assertEquals(listOf(1L,2L,3L,4L),load(rows).map { it.id })
        assertEquals(listOf(0,1,2,1),load(rows).map { it.depth }); assertTrue(load(rows)[1].current)
        assertEquals(load(rows),load(rows.reversed()))
    }
    @Test fun lockedAncestorAndChildMetadataNeverDisplay()=runBlocking {
        val rows=listOf(parent.copy(archived=true,title="secret"),current,BranchRecord(3,2,"hidden",2,true))
        assertEquals(listOf(current.title),load(rows).map { it.title }); assertEquals(3,load(rows,archive=true).size)
    }
    @Test fun deletedBranchesAndCurrentFailClosed()=runBlocking {
        assertEquals(listOf(2L),load(listOf(current)).map { it.id }); assertTrue(load(listOf(parent)).isEmpty())
    }
    @Test fun legacyLinearConversationRemainsUsable()=runBlocking { val result=load(listOf(parent),1)
        assertEquals(listOf(BranchMapNode(1,"parent",0,true)),result) }
    @Test fun cyclesAreFiniteWithoutGhostNodes()=runBlocking {
        val result=load(listOf(parent.copy(parentId=2),current)); assertEquals(2,result.size); assertEquals(2,result.map { it.id }.distinct().size)
    }
    @Test fun graphAndQueryWorkAreBounded()=runBlocking {
        val rows=listOf(parent,current)+(3L..500L).map { BranchRecord(it,2,"child$it",it) }
        var calls=0
        val result=BranchMapPolicy.load(2,false,{ id -> rows.firstOrNull { it.id==id } },{ id,limit -> calls++; assertTrue(limit<=32); rows.filter { it.parentId==id }.take(limit) })
        assertTrue(result.size<=40); assertTrue(calls<=40); assertEquals(34,result.size)
    }
    @Test fun deepAncestryAndLabelsAreBounded()=runBlocking {
        val rows=(1L..100L).map { BranchRecord(it,if(it>1)it-1 else null,"x".repeat(200),it) }
        val result=load(rows,100); assertTrue(result.size<=9); assertTrue(result.all { it.depth<=4 && it.title.length<=160 })
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun staleControllerLoadCannotReplaceNewOwner()=runTest {
        val gate=CompletableDeferred<Unit>()
        val source=object:BranchMapSource {
            override suspend fun load(id:Long):List<BranchMapNode> { if(id==1L) withContext(NonCancellable) { gate.await() }; return listOf(BranchMapNode(id,"node$id",0,true)) }
            override suspend fun accessible(id:Long)=true
        }
        val controller=BranchMapController(this,source)
        controller.load(1); runCurrent(); controller.load(2); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(2L,controller.state.value.nodes.single().id); controller.close(); assertTrue(controller.state.value.nodes.isEmpty())
    }
}
