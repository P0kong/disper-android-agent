package com.example.aiuseagent.data.execution

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PermissionControllerTest {
    @Test fun fullRequiresConfirmationAndNeverPersistsAcrossProcesses()=runTest {
        val saved=mutableMapOf<Long,String>();var invalidations=0
        fun controller()=PermissionController({ saved[it] },{ id,level->saved[id]=level },{ invalidations++ })
        val first=controller()
        assertEquals(PermissionLevel.VIEW_ONLY,first.snapshot(1).level)
        assertFalse(first.select(1,PermissionLevel.FULL,false))
        assertTrue(first.select(1,PermissionLevel.WORKSPACE_EDIT,false))
        assertTrue(first.select(1,PermissionLevel.FULL,true))
        assertEquals("WORKSPACE_EDIT",saved[1])
        assertEquals(PermissionLevel.FULL,first.snapshot(1).level)
        assertEquals(PermissionLevel.WORKSPACE_EDIT,controller().snapshot(1).level)
        assertEquals(PermissionLevel.VIEW_ONLY,controller().snapshot(2).level)
        assertEquals(2,invalidations)
    }
    @Test fun downgradeInvalidatesOutstandingPermissionSnapshotAndOtherSessionsStaySeparate()=runTest {
        val controller=PermissionController({"VIEW_ONLY"},{_,_->},{})
        controller.select(1,PermissionLevel.FULL,true)
        val elevated=controller.snapshot(1)
        controller.select(1,PermissionLevel.VIEW_ONLY,false)
        assertTrue(controller.snapshot(1).revision>elevated.revision)
        assertEquals(PermissionLevel.VIEW_ONLY,controller.snapshot(2).level)
    }
}
