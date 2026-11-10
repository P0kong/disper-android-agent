package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.AttachmentFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AttachmentReadLeaseTest {
    private fun token()=UUID.randomUUID().toString()
    @Test fun heldReaderProtectsEveryObjectUntilClose() {
        val registry=AttachmentLeaseRegistry();val source=token();val image=token()
        val lease=registry.acquire(listOf(source,image,source))
        assertNull(registry.beginDelete(source));assertNull(registry.beginDelete(image))
        lease.close()
        requireNotNull(registry.beginDelete(source)).close()
        requireNotNull(registry.beginDelete(image)).close()
    }
    @Test fun closingTwiceCannotReleaseAnotherReader() {
        val registry=AttachmentLeaseRegistry();val token=token()
        val first=registry.acquire(listOf(token));val second=registry.acquire(listOf(token))
        first.close();first.close();assertNull(registry.beginDelete(token))
        second.close();requireNotNull(registry.beginDelete(token)).close()
    }
    @Test fun deletionExcludesReadersAndOtherCollectors() {
        val registry=AttachmentLeaseRegistry();val token=token()
        val deletion=requireNotNull(registry.beginDelete(token))
        assertNull(registry.beginDelete(token))
        assertEquals("OBJECT_BUSY",assertThrows(AttachmentFailure::class.java) {registry.acquire(listOf(token))}.code)
        deletion.close();registry.acquire(listOf(token)).close()
    }
    @Test fun failedGroupAcquisitionDoesNotLeavePartialLeases() {
        val registry=AttachmentLeaseRegistry();val first=token();val busy=token()
        val deletion=requireNotNull(registry.beginDelete(busy))
        assertThrows(AttachmentFailure::class.java) {registry.acquire(listOf(first,busy))}
        requireNotNull(registry.beginDelete(first)).close();deletion.close()
    }
    @Test fun oldCollectorClosedTwiceCannotReleaseNewCollector() {
        val registry=AttachmentLeaseRegistry();val token=token()
        val first=requireNotNull(registry.beginDelete(token));first.close()
        val second=requireNotNull(registry.beginDelete(token));first.close()
        assertThrows(AttachmentFailure::class.java) {registry.acquire(listOf(token))}
        second.close();registry.acquire(listOf(token)).close()
    }
    @Test fun externalPathsAndEmptyGroupsNeverBecomeOwnedTokens() {
        val registry=AttachmentLeaseRegistry()
        for(value in listOf("../source","content://private/source","/tmp/private","","AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
            assertThrows(IllegalArgumentException::class.java) {registry.acquire(listOf(value))}
            assertThrows(IllegalArgumentException::class.java) {registry.beginDelete(value)}
        }
        assertThrows(IllegalArgumentException::class.java) {registry.acquire(emptyList())}
    }
}
