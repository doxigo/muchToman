package com.doxigo.muchtoman

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_780_000_000_000L

    private inline fun <T : androidx.room.RoomDatabase, R> T.use(block: (T) -> R): R =
        try { block(this) } finally { close() }

    private fun schemaDatabase(version: Int, name: String, body: String = "saved message"): File {
        val schema = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.doxigo.muchtoman.DurableDb/$version.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            for (entity in schema["entities"]!!.jsonArray) {
                val table = entity.jsonObject
                val tableName = table["tableName"]!!.jsonPrimitive.content
                fun execute(template: String) = sql.execSQL(template.replace("\${TABLE_NAME}", tableName))
                execute(table["createSql"]!!.jsonPrimitive.content)
                for (index in table["indices"]?.jsonArray.orEmpty()) {
                    execute(index.jsonObject["createSql"]!!.jsonPrimitive.content)
                }
            }
            for (query in schema["setupQueries"]!!.jsonArray) sql.execSQL(query.jsonPrimitive.content)
            sql.version = version
            sql.execSQL(
                "INSERT INTO sms_source VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("preserved", "09999920000", "9999920000", body, now, now, 1),
            )
            if (version >= 2) sql.execSQL(
                "INSERT INTO balance_anchor VALUES ('anchor', 'SAMAN', ?, 987654321, 'user', ?, ?, 0)",
                arrayOf(now, now, now),
            )
            if (version >= 6) sql.execSQL(
                "INSERT INTO family_txn (id,owner_member_id,source_kind,at,day,amount_rial,bank,merchant,updated_at,deleted) " +
                    "VALUES ('family-row','member','manual',1,1,-123456,'MANUAL','saved merchant',1,0)",
            )
            if (version >= 5) sql.execSQL(
                "INSERT INTO goal (id,name_fa,target_rial,kind,category_id,period,starts_on,ends_on," +
                    "created_at,updated_at,deleted" +
                    (if (version >= 10) ",shared,owner_member_id,edited_by_member_id" else "") +
                    ") VALUES ('goal','saved goal',123456789,'save',NULL,'month',1,NULL,1,1,0" +
                    (if (version >= 10) ",0,'',''" else "") + ")",
            )
        }
        return file
    }

    @Test
    fun `every historical schema opens through real Room migrations without losing user data`() = runBlocking {
        for (version in 1 until DURABLE_DB_VERSION) {
            val file = schemaDatabase(version, "migration-$version.db")
            DurableDb.builder(context, file.path).build().use { db ->
                assertEquals("version $version", "saved message", db.smsSource().bodyOf("preserved"))
                assertEquals(DURABLE_DB_VERSION, db.openHelper.writableDatabase.version)
                if (version >= 2) assertEquals(987654321L, db.anchors().newest("SAMAN")!!.balanceRial)
                if (version >= 6) {
                    val shared = db.familyTxns().get("family-row")!!
                    assertEquals(-123456L, shared.amountRial)
                    assertFalse(shared.transfer)
                }
                if (version >= 5) {
                    val goal = db.goals().active().single()
                    assertEquals(123456789L, goal.targetRial)
                    assertFalse(goal.shared)
                    assertEquals("", goal.ownerMemberId)
                }
            }
        }
    }

    @Test
    fun `empty initial scan preserves a boundary for later arrivals without importing history`() = runBlocking {
        DurableDb.builder(context, "ingest.db").build().use { db ->
            val inbox = mutableListOf(RawSms("09999920000", "old", now - 1))
            suspend fun scan(at: Long) = ingestBankSms(db, emptyMap(), at) { since ->
                inbox.filter { it.at >= since }.sortedBy { it.at }
            }
            assertEquals(0, scan(now))
            inbox += RawSms("09999920000", "new", now + 10)
            assertEquals(1, scan(now + 20))
            assertEquals(listOf("new"), db.smsSource().allOldestFirst().map { it.body })
            assertEquals(0, scan(now + 30))
            inbox += RawSms("09999920000", "same timestamp", now + 10)
            assertEquals(1, scan(now + 40))
            assertEquals(2, db.smsSource().count())
        }
    }

    @Test
    fun `legacy preference upgrade keeps anchored balances for durable migration`() = runBlocking {
        val prefs = context.getSharedPreferences("muchtoman", 0)
        prefs.edit().putInt("smsSchema", 11).putString(
            "bankAccounts",
            """[{"bank":"SAMAN","balance":123456.7,"updatedAt":100,"anchored":true}]""",
        ).commit()
        val store = Store(context)
        assertTrue(store.smsFoldNeedsRefresh)
        val exported = exportablePrefs(context)
        store.smsFoldNeedsRefresh = false
        applyRestoredPrefs(context, exported)
        assertTrue(Store(context).smsFoldNeedsRefresh)
        assertEquals(123456.7, store.bankAccounts.single().balance, 0.0)
        DurableDb.builder(context, "anchor-upgrade.db").build().use { db ->
            migrateAnchorsFromPrefs(store.bankAccounts, db, now)
            assertEquals(1234567L, db.anchors().newest("SAMAN")!!.balanceRial)
            migrateAnchorsFromPrefs(store.bankAccounts, db, now + 1)
            assertEquals(1, db.anchors().count())
        }
    }

    @Test
    fun `complete ledger and goal include the spending before four thousand newest rows`() = runBlocking {
        DurableDb.builder(context, "large-ledger.db").build().use { durable ->
            androidx.room.Room.databaseBuilder(context, DerivedDb::class.java, "large-derived.db")
                .build().use { derived ->
                    val rows = (0..4000).map { index ->
                        manualToRow(ManualTxn(
                            id = "row-$index", at = now + index, day = tehranDay(now),
                            amountRial = if (index == 0) -10_000 else 1,
                            createdAt = now, updatedAt = now,
                        ))
                    }
                    derived.txn().insertAll(rows)
                    durable.goals().put(Goal(
                        id = "saving", nameFa = "saving", targetRial = 1_000_000,
                        kind = GoalKind.SAVE, period = GoalPeriod.ONCE,
                        startsOn = tehranDay(now) - 1, createdAt = now, updatedAt = now,
                    ))
                    val view = ledgerView(derived, durable)
                    assertEquals(4001, view.entries.size)
                    assertTrue(view.entries.any { it.txn.ref == "m:row-0" })
                    assertEquals(-6000L, view.entries.sumOf { it.txn.signedRial ?: 0 })
                    assertEquals(0L, view.goals.single().currentRial)
                    assertEquals(100, ledgerEntries(derived, durable, limit = 100).entries.size)
                }
        }
    }

    @Test
    fun `overlapping derives cannot replace a newer source snapshot with an older one`() = runBlocking {
        val paused = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val queryThreads = java.util.concurrent.Executors.newFixedThreadPool(4)
        val writerThread = java.util.concurrent.Executors.newSingleThreadExecutor()
        val durable = DurableDb.builder(context, "concurrent-durable.db")
            .setQueryExecutor(queryThreads)
            .setTransactionExecutor(writerThread)
            .setQueryCallback(object : androidx.room.RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery == "SELECT * FROM link_decision WHERE deleted = 0" && first.compareAndSet(true, false)) {
                        paused.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }, java.util.concurrent.Executor { it.run() })
            .build()
        val derived = androidx.room.Room.databaseBuilder(context, DerivedDb::class.java, "concurrent-derived.db").build()
        try {
            val source = SmsSource(
                srcHash = "first", sender = "100031", addrKey = "100031",
                body = "بانک رفاه واریز مبلغ 900,000 ریال مانده 1,000,000 ریال",
                at = now, ingestedAt = now,
            )
            durable.smsSource().insertAll(listOf(source))
            val older = async(Dispatchers.IO) { derive(durable, derived, emptyMap(), now) }
            assertTrue(withContext(Dispatchers.IO) { paused.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            durable.smsSource().insertAll(listOf(source.copy(srcHash = "second", at = now + 1)))
            val entered = CompletableDeferred<Unit>()
            val newer = async(Dispatchers.IO) {
                entered.complete(Unit)
                derive(durable, derived, emptyMap(), now + 2)
            }
            entered.await()
            try {
                assertNull("a newer rebuild must not pass the paused older snapshot", withTimeoutOrNull(1000) { newer.await() })
            } finally {
                release.countDown()
            }
            older.await()
            newer.await()
            assertEquals(setOf("first", "second"), derived.txn().newest(10).map { it.srcHash }.toSet())
        } finally {
            release.countDown()
            durable.close()
            derived.close()
            queryThreads.shutdownNow()
            writerThread.shutdownNow()
        }
    }

    @Test
    fun `backup copy contains live WAL data and removes every device credential`() = runBlocking {
        DurableDb.builder(context, "backup-source.db").build().use { db ->
            for (key in BACKUP_STRIPPED_META) db.meta().put(DurableMeta(key, "test-only"))
            db.meta().put(DurableMeta("ordinary", "keep"))
            db.anchors().put(BalanceAnchor("a", "SAMAN", now, 7654321, "user", now, now))
            val bytes = backupDurableDbBytes(context, db)
            val copy = context.getDatabasePath("backup-copy.db").apply { writeBytes(bytes) }
            DurableDb.builder(context, copy.path).build().use { restored ->
                assertEquals(7654321L, restored.anchors().newest("SAMAN")!!.balanceRial)
                assertEquals("keep", restored.meta().get("ordinary"))
                for (key in BACKUP_STRIPPED_META) assertNull(key, restored.meta().get(key))
            }
            assertEquals("test-only", db.meta().get(BACKUP_STRIPPED_META.first()))
        }
    }

    @Test
    fun `newer schema and invalid database leave current data and preferences untouched`() {
        val original = schemaDatabase(DURABLE_DB_VERSION, "durable.db", "original")
        val originalBytes = original.readBytes()
        context.getSharedPreferences("muchtoman", 0).edit().putString("name", "original").commit()
        val newer = schemaDatabase(DURABLE_DB_VERSION, "newer.db")
        SQLiteDatabase.openDatabase(newer.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = DURABLE_DB_VERSION + 1
        }
        val fault = assertThrows(BackupException::class.java) {
            stageRestore(context, newer.readBytes(), "{}")
        }
        assertEquals(BackupFault.NEWER_FORMAT, fault.fault)
        assertThrows(Exception::class.java) { stageRestore(context, byteArrayOf(1, 2, 3), "{}") }
        val wrongShape = context.getDatabasePath("wrong-shape.db")
        SQLiteDatabase.openOrCreateDatabase(wrongShape, null).use {
            it.execSQL("CREATE TABLE unrelated (id INTEGER)")
            it.version = DURABLE_DB_VERSION
        }
        assertThrows(IllegalStateException::class.java) { stageRestore(context, wrongShape.readBytes(), "{}") }
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals("original", context.getSharedPreferences("muchtoman", 0).getString("name", null))
        assertFalse(File(original.parentFile, "durable.db.restore-ready").exists())
    }

    @Test
    fun `restore migrates its staged copy and installs both halves only after validation`() = runBlocking {
        schemaDatabase(DURABLE_DB_VERSION, "durable.db", "original")
        val backup = schemaDatabase(9, "older.db", "restored")
        val prefs = context.getSharedPreferences("muchtoman", 0)
        prefs.edit().putString("name", "original").commit()
        stageRestore(context, backup.readBytes(), encodeBackupPrefs(mapOf("name" to BackupPref("s", "restored"))))
        assertEquals("original", prefs.getString("name", null))
        assertTrue(finishStagedRestore(context))
        DurableDb.builder(context, "durable.db").build().use { db ->
            assertEquals("restored", db.smsSource().bodyOf("preserved"))
            assertEquals(987654321L, db.anchors().newest("SAMAN")!!.balanceRial)
        }
        assertEquals("restored", prefs.getString("name", null))
        assertFalse(finishStagedRestore(context))
    }

    @Test
    fun `failed completion rolls back database and prefs then safely retries`() = runBlocking {
        val original = schemaDatabase(DURABLE_DB_VERSION, "durable.db", "original")
        val backup = schemaDatabase(9, "older.db", "restored")
        val prefs = context.getSharedPreferences("muchtoman", 0)
        prefs.edit().putString("name", "original").commit()
        stageRestore(context, backup.readBytes(), encodeBackupPrefs(mapOf("name" to BackupPref("s", "restored"))))
        val blockedCache = File(original.parentFile, "derived.db").apply { mkdir() }
        File(blockedCache, "block-deletion").writeText("test")
        assertThrows(IllegalStateException::class.java) { finishStagedRestore(context) }
        assertEquals("original", prefs.getString("name", null))
        DurableDb.builder(context, "durable.db").build().use { db ->
            assertEquals("original", db.smsSource().bodyOf("preserved"))
        }
        blockedCache.deleteRecursively()
        assertTrue(finishStagedRestore(context))
        assertEquals("restored", prefs.getString("name", null))
    }

    @Test
    fun `startup recovers an interrupted swap from its original files before retrying`() = runBlocking {
        val original = schemaDatabase(DURABLE_DB_VERSION, "durable.db", "original")
        val backup = schemaDatabase(9, "older.db", "restored")
        val dir = original.parentFile!!
        val prefs = context.getSharedPreferences("muchtoman", 0)
        prefs.edit().putString("name", "original").commit()
        stageRestore(context, backup.readBytes(), encodeBackupPrefs(mapOf("name" to BackupPref("s", "restored"))))
        original.copyTo(File(dir, "durable.db.restore-original"))
        File(dir, "durable.db.restore-original-prefs").writeText(encodeBackupPrefs(exportablePrefs(context)))
        File(dir, "durable.db.restore-original-ready").writeBytes(byteArrayOf())
        original.writeBytes(byteArrayOf(1, 2, 3))
        prefs.edit().putString("name", "partially changed").commit()
        assertTrue(finishStagedRestore(context))
        DurableDb.builder(context, "durable.db").build().use { db ->
            assertEquals("restored", db.smsSource().bodyOf("preserved"))
        }
        assertEquals("restored", prefs.getString("name", null))
    }
}
