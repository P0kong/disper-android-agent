package com.example.aiuseagent.data

import org.junit.Assert.*
import org.junit.Test

class WorkFolderManagerTest {
    private class Store : WorkFolderStore {
        var selected: SavedWorkFolder? = null
        override fun read() = selected
        override fun save(folder: SavedWorkFolder?) { selected = folder }
    }
    private class Access : DirectoryAccess {
        val grants = mutableMapOf<String, FolderGrant>()
        val released = mutableListOf<String>()
        var failTake = false
        var failRelease = false
        var missing = false
        override fun take(uri: String, flags: Int) {
            if (failTake || flags and 1 == 0) throw SecurityException("test-only")
            grants[uri] = FolderGrant(true, flags and 2 != 0)
        }
        override fun grant(uri: String) = grants[uri]
        override fun displayName(uri: String) = if (missing) null else "Synthetic " + uri.last()
        override fun release(uri: String) { if (failRelease) throw SecurityException(); grants.remove(uri); released.add(uri) }
    }
    private val store = Store()
    private val access = Access()
    private val manager = WorkFolderManager(store, access)
    private val a = "content://synthetic/tree/A"
    private val b = "content://synthetic/tree/B"

    @Test fun selectSavesOnlyDirectoryReferenceAndRestartRechecksActualGrant() {
        manager.select(a, 3); assertEquals(SavedWorkFolder(a, "Synthetic A"), store.selected)
        val reopened = WorkFolderManager(store, access); assertEquals(FolderStatus.CHECKING, reopened.state.value.status)
        reopened.refresh(); assertEquals(FolderStatus.AUTHORIZED, reopened.state.value.status); assertTrue(reopened.state.value.writable)
    }
    @Test fun cancelKeepsSelectionAndGrant() {
        manager.select(a, 3); manager.select(null, 0)
        assertEquals(a, store.selected?.uri); assertEquals(FolderStatus.AUTHORIZED, manager.state.value.status); assertTrue(access.released.isEmpty())
    }
    @Test fun replacementReleasesPreviousGrantOnly() {
        access.grants["content://synthetic/unrelated"] = FolderGrant(true, true)
        manager.select(a, 3); manager.select(b, 1)
        assertEquals(b, store.selected?.uri); assertEquals(listOf(a), access.released); assertFalse(manager.state.value.writable)
        assertTrue(access.grants.containsKey("content://synthetic/unrelated")); assertFalse(access.grants.containsKey(a))
    }
    @Test fun removeClearsPersistedStateAndOwnGrant() {
        manager.select(a, 3); manager.remove()
        assertNull(store.selected); assertEquals(FolderStatus.NONE, manager.state.value.status); assertFalse(access.grants.containsKey(a))
    }
    @Test fun revokedGrantIsShownAsNeedingReselectionWithoutAutomaticRegrant() {
        manager.select(a, 3); access.grants.clear(); manager.refresh()
        assertEquals(FolderStatus.RESELECT, manager.state.value.status); assertEquals(a, store.selected?.uri); assertTrue(access.grants.isEmpty())
    }
    @Test fun inaccessibleDirectoryIsNotReportedAuthorizedEvenWithPersistedGrant() {
        manager.select(a, 3); access.missing = true; manager.refresh()
        assertEquals(FolderStatus.RESELECT, manager.state.value.status); assertFalse(manager.state.value.writable)
    }
    @Test fun failedReplacementRetainsPreviousAndRollsBackNewGrant() {
        manager.select(a, 3); access.missing = true; manager.select(b, 3)
        assertEquals(a, store.selected?.uri); assertFalse(access.grants.containsKey(b)); assertNotNull(manager.state.value.notice)
    }
    @Test fun deniedTakeDoesNotOverwriteExistingChoice() {
        manager.select(a, 3); access.failTake = true; manager.select(b, 3)
        assertEquals(a, store.selected?.uri); assertEquals(FolderStatus.AUTHORIZED, manager.state.value.status); assertNotNull(manager.state.value.notice)
    }
    @Test fun failedRemovalKeepsReferenceSoUserCanRetry() {
        manager.select(a, 3); access.failRelease = true; manager.remove()
        assertEquals(a, store.selected?.uri); assertNotNull(manager.state.value.notice)
        access.failRelease = false; manager.remove(); assertNull(store.selected)
    }
}
