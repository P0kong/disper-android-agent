package com.example.aiuseagent.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 导出的 schema 契约测试。
 *
 * 它读的是 KSP/Room 在构建过程中真实生成的
 * app/schemas/com.example.aiuseagent.data.local.AppDatabase/1.json，
 * 因此校验的是「真正会建到 SQLite 里的结构」，包括外键级联与索引。
 *
 * 说明：本工程不引入 Robolectric，所以这里不做 SQL 执行级测试；
 * 真实 SQLite 上的增删改查必须在设备/模拟器上验证（见 docs/MANUAL_TEST_PLAN.md）。
 */
class RoomSchemaContractTest {

    private val relativePath = "schemas/com.example.aiuseagent.data.local.AppDatabase/1.json"

    private fun schemaText(): String {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath",
            "../../app/$relativePath",
        )
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError(
            "找不到 Room schema 导出文件，尝试过的路径：" +
                candidates.joinToString() +
                "（当前工作目录 " + File(".").absolutePath + "）",
        )
    }

    @Test
    fun schemaDeclaresSessionsTable() {
        val text = schemaText()
        assertTrue("schema 里缺少 sessions 表", text.contains("\"tableName\": \"sessions\""))
        assertTrue(
            "sessions 表的建表语句与预期不一致",
            text.contains(
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            ),
        )
    }

    @Test
    fun schemaDeclaresMessagesTableWithCascadeForeignKey() {
        val text = schemaText()
        assertTrue("schema 里缺少 messages 表", text.contains("\"tableName\": \"messages\""))
        assertTrue(
            "messages 表的列定义与预期不一致",
            text.contains(
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isDemo` INTEGER NOT NULL",
            ),
        )
        assertTrue("messages 缺少指向 sessions 的外键", text.contains("REFERENCES `sessions`(`id`)"))
        assertTrue("外键必须 ON DELETE CASCADE，否则删会话会留下孤儿消息", text.contains("ON DELETE CASCADE"))
        assertTrue("外键更新策略应为 NO ACTION", text.contains("ON UPDATE NO ACTION"))
    }

    @Test
    fun schemaDeclaresSessionIdIndex() {
        val text = schemaText()
        assertTrue(
            "缺少 sessionId 索引",
            text.contains("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `\${TABLE_NAME}` (`sessionId`)"),
        )
    }

    @Test
    fun schemaVersionIsOne() {
        val text = schemaText()
        assertTrue("Room schema 版本应为 1", text.contains("\"version\": 1"))
        assertTrue("缺少 identityHash（迁移校验依赖它）", text.contains("\"identityHash\""))
    }
}
