package com.doxigo.muchtoman

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A backup file is untrusted input: one put together by hand can carry a ready-made sync session,
 * or a trigger that writes one back. Restore must drop the session rows and refuse triggers and views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private inline fun <T : androidx.room.RoomDatabase, R> T.use(block: (T) -> R): R =
        try { block(this) } finally { close() }

    /** The bytes of a database as a hand-edited backup would hold them — not through `backupDurableDbBytes`, which strips. */
    private suspend fun craftedBytes(name: String, extraSql: List<String> = emptyList()): ByteArray {
        DurableDb.builder(context, name).build().use { db ->
            for (key in BACKUP_STRIPPED_META) db.meta().put(DurableMeta(key, "planted"))
            db.meta().put(DurableMeta("ordinary", "keep"))
            // Fold the WAL into the main file, so the bytes read below hold the rows.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        val file = context.getDatabasePath(name)
        if (extraSql.isNotEmpty()) {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                extraSql.forEach(sql::execSQL)
            }
        }
        return file.readBytes()
    }

    private fun stagedMarker() = File(context.getDatabasePath("durable.db").parentFile, "durable.db.restore-ready")

    @Test
    fun `a session planted in a backup does not survive the restore`() = runBlocking {
        stageRestore(context, craftedBytes("crafted.db"), "{}")
        assertTrue(finishStagedRestore(context))
        DurableDb.builder(context, "durable.db").build().use { db ->
            for (key in BACKUP_STRIPPED_META) assertNull(key, db.meta().get(key))
            assertEquals("keep", db.meta().get("ordinary"))
            assertNull(loadSession(db))
        }
    }

    @Test
    fun `a file with a trigger is refused and nothing is staged`() = runBlocking {
        val bytes = craftedBytes(
            "trigger.db",
            listOf("CREATE TRIGGER put_back AFTER DELETE ON durable_meta BEGIN INSERT OR REPLACE INTO durable_meta (k, v) VALUES ('sync_base', 'x'); END"),
        )
        assertThrows(IllegalStateException::class.java) { stageRestore(context, bytes, "{}") }
        assertFalse(stagedMarker().exists())
    }

    @Test
    fun `a file with a view is refused and nothing is staged`() = runBlocking {
        val bytes = craftedBytes("view.db", listOf("CREATE VIEW peek AS SELECT k FROM durable_meta"))
        assertThrows(IllegalStateException::class.java) { stageRestore(context, bytes, "{}") }
        assertFalse(stagedMarker().exists())
    }
}
