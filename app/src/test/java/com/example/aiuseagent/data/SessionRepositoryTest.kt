package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionRepository 的 JVM 单元测试：全部断言都对着「数据库」里的真实行。 */
class SessionRepositoryTest {

    private val store = FakeDaoStore()
    private var clock = 1_000L

    private fun repository() = SessionRepository(store.sessionDao, store.messageDao) { clock }

    private fun tick() {
        clock += 1_000L
    }

    @Test
    fun createSession_withoutTitle_usesDefaultTitle() = runTest {
        val repository = repository()
        val id = repository.createSession()

        val row = store.sessionDao.findById(id)
        assertNotNull(row)
        assertEquals(SessionRepository.DEFAULT_TITLE, row!!.title)
        assertEquals(clock, row.createdAt)
        assertEquals(clock, row.updatedAt)
    }

    @Test
    fun createSession_trimsExplicitTitle() = runTest {
        val repository = repository()
        val id = repository.createSession("   项目讨论   ")
        assertEquals("项目讨论", store.sessionDao.findById(id)!!.title)
    }

    @Test
    fun observeSessions_returnsNewestUpdatedFirst() = runTest {
        val repository = repository()
        val first = repository.createSession("第一个")
        tick()
        val second = repository.createSession("第二个")

        var rows = repository.observeSessions().first()
        assertEquals(listOf(second, first), rows.map { it.id })

        // 更新第一个会话后，它应该排到最前面（证明排序来自数据库字段，不是新建顺序）。
        tick()
        assertEquals(RenameResult.Success, repository.renameSession(first, "第一个（改名）"))
        rows = repository.observeSessions().first()
        assertEquals(listOf(first, second), rows.map { it.id })
    }

    @Test
    fun renameSession_rejectsBlankTitle() = runTest {
        val repository = repository()
        val id = repository.createSession("原标题")
        assertEquals(RenameResult.BlankTitle, repository.renameSession(id, "   "))
        assertEquals("原标题", store.sessionDao.findById(id)!!.title)
    }

    @Test
    fun renameSession_rejectsTooLongTitle() = runTest {
        val repository = repository()
        val id = repository.createSession("原标题")
        val tooLong = "x".repeat(SessionRepository.MAX_TITLE_LENGTH + 1)

        assertEquals(RenameResult.TooLong, repository.renameSession(id, tooLong))
        assertEquals("原标题", store.sessionDao.findById(id)!!.title)

        val atLimit = "y".repeat(SessionRepository.MAX_TITLE_LENGTH)
        assertEquals(RenameResult.Success, repository.renameSession(id, atLimit))
        assertEquals(atLimit, store.sessionDao.findById(id)!!.title)
    }

    @Test
    fun renameSession_reportsMissingSession() = runTest {
        val repository = repository()
        assertEquals(RenameResult.NotFound, repository.renameSession(999L, "随便"))
    }

    @Test
    fun deleteSession_removesSessionAndItsMessages() = runTest {
        val repository = repository()
        val kept = repository.createSession("保留")
        tick()
        val removed = repository.createSession("删除")
        repository.appendMessage(kept, SessionRepository.ROLE_USER, "留在库里")
        repository.appendMessage(removed, SessionRepository.ROLE_USER, "会一起删掉")
        assertEquals(2, store.messageCount())

        repository.deleteSession(removed)

        assertEquals(1, store.sessionCount())
        assertEquals(1, store.messageCount())
        assertNull(store.sessionDao.findById(removed))
        assertEquals(1, store.messages.value.count { it.sessionId == kept })
    }

    @Test
    fun appendMessage_storesRowAndTouchesSession() = runTest {
        val repository = repository()
        val id = repository.createSession("会话")
        tick()
        val messageId = repository.appendMessage(id, SessionRepository.ROLE_USER, "你好")

        val row = store.messages.value.single()
        assertEquals(messageId, row.id)
        assertEquals(id, row.sessionId)
        assertEquals(SessionRepository.ROLE_USER, row.role)
        assertEquals("你好", row.content)
        assertFalse(row.isDemo)
        assertEquals(clock, row.createdAt)
        // 会话的 updatedAt 被 touch 到消息时间
        assertEquals(clock, store.sessionDao.findById(id)!!.updatedAt)
        assertEquals(1, repository.messageCount(id))
    }

    @Test
    fun seedDemoMessageIfEmpty_onlyInsertsOnce() = runTest {
        val repository = repository()
        val id = repository.createSession("会话")

        repository.seedDemoMessageIfEmpty(id)
        repository.seedDemoMessageIfEmpty(id)

        val rows = store.messages.value
        assertEquals(1, rows.size)
        assertTrue(rows.single().isDemo)
        assertEquals(SessionRepository.ROLE_ASSISTANT, rows.single().role)
        assertEquals(SessionRepository.DEMO_MESSAGE, rows.single().content)
    }

    @Test
    fun dataSurvivesRepositoryRecreation_simulatingProcessRestart() = runTest {
        val first = repository()
        val id = first.createSession("重启前")
        first.appendMessage(id, SessionRepository.ROLE_USER, "重启前写入")

        // 新的 Repository 实例 + 同一份 DAO 数据 == 进程被杀后重新打开数据库。
        val second = repository()
        val sessions = second.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals("重启前", sessions.single().title)
        assertEquals(1, second.messageCount(id))
        assertEquals("重启前写入", second.observeMessages(id).first().single().content)
    }

    @Test
    fun observeMessages_ordersByCreatedAtThenId() = runTest {
        val repository = repository()
        val id = repository.createSession("会话")
        repository.appendMessage(id, SessionRepository.ROLE_USER, "第一条")
        repository.appendMessage(id, SessionRepository.ROLE_ASSISTANT, "第二条")
        repository.appendMessage(id, SessionRepository.ROLE_USER, "第三条")

        val contents = repository.observeMessages(id).first().map { it.content }
        assertEquals(listOf("第一条", "第二条", "第三条"), contents)
    }

    @Test
    fun findSession_returnsNullForUnknownId() = runTest {
        val repository = repository()
        assertNull(repository.findSession(404L))
        val id = repository.createSession("存在")
        val entity: SessionEntity? = repository.findSession(id)
        assertNotNull(entity)
        assertEquals(id, entity!!.id)
    }
}
