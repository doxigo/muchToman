# Plan 012: A restored backup never brings a sync session with it (Android)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 3e6246e..HEAD -- app/src/main/java/com/doxigo/muchtoman/Ledger.kt`
> If `Ledger.kt` changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition. (Plans 010 and 011 run earlier in the same
> worktree; they do not touch `Ledger.kt`.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW (a genuine backup never contains the rows being removed)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `3e6246e`, 2026-11-20

## Why this matters

A backup file (`.mtbak`) is the app's durable database, encrypted with a passphrase. When the
app **makes** one, it deletes the family-sync session from the copy — server address, device
token, household key, cursor — so a file can never act as the phone it came from.

When the app **restores** one, it checks the file's version and integrity and installs it
as-is. Nothing removes those rows on the way in. A file that was put together by hand can
therefore carry a ready-made session: a foreign server address, a token, and a key its author
knows. After the restore the phone would sync its ledger there. It needs someone to restore a
file and passphrase they were handed, so it is social engineering — but it is the same hole
plan 004 closed for pairing links, left open at a second door.

The fix is to strip the same rows on import that export already strips, and to refuse a file
that carries SQL triggers or views (which could write the rows back).

## Current state

All in `app/src/main/java/com/doxigo/muchtoman/Ledger.kt`.

The list, and what export does with it (~L1029–1033, ~L1069–1075):

```kotlin
val BACKUP_STRIPPED_META: List<String> = listOf(
    META_SYNC_BASE, META_SYNC_TOKEN, META_SYNC_TOKEN_AT, META_SYNC_DEVICE, META_SYNC_MEMBER,
    META_SYNC_SCOPE, META_SYNC_KEY, META_SYNC_SEQ, META_SYNC_IDENTITY_OK, META_SYNC_SHARE_SMS,
    META_SYNC_ROTATION, META_SYNC_READ,
)
```

```kotlin
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READWRITE).use { plain ->
                for (key in BACKUP_STRIPPED_META) {
                    plain.execSQL("DELETE FROM durable_meta WHERE k = ?", arrayOf(key))
                }
```

The meta table is `@Entity(tableName = "durable_meta") data class DurableMeta(@PrimaryKey val k: String, val v: String)`.

The import side, which this plan changes (~L1107–1130):

```kotlin
/** Opens the actual Room schema, including migrations, before any live data is replaced. */
internal fun validateRestoreDatabase(context: Context, file: File) {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
        if (sql.version > DURABLE_DB_VERSION) {
            throw BackupException(BackupFault.NEWER_FORMAT, "newer database schema")
        }
        require(sql.version in 1..DURABLE_DB_VERSION) { "unsupported database schema" }
        sql.rawQuery("PRAGMA integrity_check", null).use { result ->
            check(result.moveToFirst() && result.getString(0) == "ok" && !result.moveToNext()) {
                "database integrity check failed"
            }
        }
    }
    val db = DurableDb.builder(context, file.absolutePath).build()
    try {
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT COUNT(*) FROM sms_source").use { check(it.moveToFirst()) }
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "database checkpoint failed" }
        }
    } finally {
        db.close()
    }
}
```

It has two callers, both in the same file:

- `stageRestore(context, dbBytes, prefsJson)` (~L1133) writes the file's bytes to a temp file,
  calls `validateRestoreDatabase` on it, then renames it to the staged copy. **This staged copy
  is the file that gets installed**, so whatever is deleted here is gone from the restore.
- `finishStagedRestore(context)` (~L1183) installs the staged copy at the next launch and calls
  `validateRestoreDatabase` once more on the installed file (with a rollback already in place).

By the time the Room half runs, migrations have brought the file to the current schema, so
`durable_meta` exists whatever version the backup was.

Facts that bound the change (read at `3e6246e`):

- The app's own schema has **no trigger and no view** in any version: nothing in
  `app/src/main/java/` creates one and the committed schemas under
  `app/schemas/com.doxigo.muchtoman.DurableDb/` (1–11) declare none. Room's own bookkeeping
  triggers are temporary and never in the file.
- The sharing switches that are *not* in the stripped list (`sync_share_assets`,
  `sync_excluded_banks`) do not need stripping: with no session nothing is sent, and every way
  of getting a session again runs `resetFamilySharing` (`Sync.kt` `buryHousehold`,
  `keepMember == null`).
- The **PWA is not affected and must not be "ported"**: its restore keeps the browser's own
  session and never takes one from the file (`pwa/src/backup.ts` `LOCAL_PREFS` /
  `LOCAL_TABLES`, and `pwa/src/family.ts` uses `location.origin` as the only sync base).
- `loadSession` (`Sync.kt` ~L651) is **deliberately left alone** — see Maintenance notes.

Existing tests to model on — `app/src/test/java/com/doxigo/muchtoman/PersistenceTest.kt`
(Robolectric, `@Config(sdk = [28])`): «backup copy contains live WAL data and removes every
device credential» (~L374) and «restore migrates its staged copy and installs both halves only
after validation» (~L429). They use `DurableDb.builder(context, "<name>.db").build().use { … }`,
`stageRestore(context, bytes, "{}")`, `finishStagedRestore(context)`.

**Conventions**: comments explain *why* in plain sentences. One flat Kotlin package.

## Commands you will need

A fresh worktree has no `local.properties`.

| Purpose | Command | Expected on success |
|---|---|---|
| Android env (once per shell) | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"` | — |
| One test class | `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.<Class>'` | `BUILD SUCCESSFUL` |
| Full gate | `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` | `BUILD SUCCESSFUL` |

If a Gradle test task reports `UP-TO-DATE` after a code change, re-run it with `--rerun`.

## Scope

**In scope**:
- `app/src/main/java/com/doxigo/muchtoman/Ledger.kt` (only `validateRestoreDatabase` and the
  KDoc of `BACKUP_STRIPPED_META` / `stageRestore`)
- `app/src/test/java/com/doxigo/muchtoman/RestoreSessionTest.kt` (create)

**Out of scope** (do NOT touch):
- `Sync.kt` `loadSession` and its ~30 callers.
- `pwa/` — not affected, see above.
- `Export.kt`, the backup envelope, `BACKUP_FORMAT_VERSION`, the `BACKUP_STRIPPED_META` list
  itself (`ExportTest` pins its exact contents).
- `DURABLE_DB_VERSION` and the migrations. This plan adds no migration.
- `PersistenceTest.kt` — add the new cases in a new file.

## Git workflow

- Commit on the worktree's current branch; one commit for this plan.
- Subject in English only, lower-case area prefix then a plain description. Suggested:
  `backup: drop the sync session from a restored file, refuse triggers and views`.
- No `Co-Authored-By` or any other trailer. Do not describe users as "she/her" in the message.
- Do NOT push, merge, or touch `main`.

## Steps

### Step 1: Refuse a file that carries triggers or views

In `validateRestoreDatabase`, inside the first (read-only) block, after the
`PRAGMA integrity_check` check, add:

```kotlin
        // The ledger's own schema has no trigger and no view, so one in a backup was put there
        // by hand — and a trigger on durable_meta could write a session back after the strip below.
        sql.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('trigger', 'view')", null).use { found ->
            check(found.moveToFirst() && found.getInt(0) == 0) { "database carries triggers or views" }
        }
```

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.PersistenceTest' --tests 'com.doxigo.muchtoman.ExportTest'` → `BUILD SUCCESSFUL`
(every existing restore test still passes — proof that genuine files, old schemas included,
carry none).

### Step 2: Strip the session rows on the way in

In the same function, in the Room half, between the `SELECT COUNT(*) FROM sms_source` check and
the `wal_checkpoint`, add:

```kotlin
        // What export leaves out, import refuses to take: a file made by this app has none of
        // these rows, so one that has them was edited — and a session it carried would point
        // this phone's sync at whoever wrote it. A restored phone pairs again, as it always did.
        for (key in BACKUP_STRIPPED_META) {
            sql.execSQL("DELETE FROM durable_meta WHERE k = ?", arrayOf<Any?>(key))
        }
```

The existing `wal_checkpoint(TRUNCATE)` right after it folds the deletes into the main file,
which is the file `stageRestore` renames into place.

Update the function's KDoc to say it also does this, e.g.:
`/** Opens the actual Room schema, including migrations, before any live data is replaced — and takes the sync session out of the copy ([BACKUP_STRIPPED_META]). */`
and add one sentence to the `BACKUP_STRIPPED_META` KDoc (just above the list) saying the same
keys are deleted from a file being restored, in `validateRestoreDatabase`.

**Verify**: `./gradlew compileFullDebugKotlin` → `BUILD SUCCESSFUL`.

### Step 3: Tests

Create `app/src/test/java/com/doxigo/muchtoman/RestoreSessionTest.kt` with the same runner
annotations and `context` accessor as `PersistenceTest.kt`:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
```

A helper that produces the bytes of a database file as a hand-edited backup would hold them
(note: **not** through `backupDurableDbBytes`, which strips):

```kotlin
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
```

Cases (use `runBlocking`, backticked names, a class KDoc saying what is pinned):

1. **A session planted in a backup does not survive the restore.**
   `stageRestore(context, craftedBytes("crafted.db"), "{}")`; `assertTrue(finishStagedRestore(context))`;
   then `DurableDb.builder(context, "durable.db").build().use { db -> … }`:
   every key in `BACKUP_STRIPPED_META` reads `null` from `db.meta().get(key)`;
   `db.meta().get("ordinary")` is `"keep"`; `loadSession(db)` is `null`.
2. **A file with a trigger is refused and nothing is staged.**
   `craftedBytes("trigger.db", listOf("CREATE TRIGGER put_back AFTER DELETE ON durable_meta BEGIN INSERT OR REPLACE INTO durable_meta (k, v) VALUES ('sync_base', 'x'); END"))`
   → `assertThrows(IllegalStateException::class.java) { stageRestore(context, bytes, "{}") }`, and
   `assertFalse(File(context.getDatabasePath("durable.db").parentFile, "durable.db.restore-ready").exists())`.
3. **A file with a view is refused** the same way
   (`"CREATE VIEW peek AS SELECT k FROM durable_meta"`).

**Verify**: `./gradlew testFullDebugUnitTest --tests 'com.doxigo.muchtoman.RestoreSessionTest'` → `BUILD SUCCESSFUL`.
To prove case 1 bites: comment out the Step 2 loop, re-run, confirm case 1 FAILS, restore it.

### Step 4: Full gate

**Verify**: `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`.

## Test plan

- New `RestoreSessionTest.kt`: the three cases above.
- Must stay green unchanged: all of `PersistenceTest` (staged restore, rollback, migration of a
  version-9 file) and `ExportTest` (the stripped list's exact contents).

## Done criteria

- [ ] `./gradlew testFullDebugUnitTest testLiteDebugUnitTest lint` → `BUILD SUCCESSFUL`
- [ ] `grep -n "for (key in BACKUP_STRIPPED_META)" app/src/main/java/com/doxigo/muchtoman/Ledger.kt` → exactly two lines (export and import)
- [ ] `grep -n "type IN ('trigger', 'view')" app/src/main/java/com/doxigo/muchtoman/Ledger.kt` → one line
- [ ] `git status --short` (before commit) lists only `Ledger.kt` and `RestoreSessionTest.kt`
- [ ] `git diff 3e6246e -- pwa/src/backup.ts app/src/main/java/com/doxigo/muchtoman/Export.kt` → empty
- [ ] One commit, no trailer

## STOP conditions

- The excerpts in "Current state" do not match the live `Ledger.kt`.
- Step 1's verification fails: an existing restore test now reports "database carries triggers
  or views". That would mean a genuine database does hold one — report which, do not weaken the check.
- `craftedBytes` yields a file whose planted rows are not in it (case 1 passes even with the
  Step 2 loop commented out) — the WAL was not folded; report rather than guess.
- `stageRestore` rejects the plain `craftedBytes("crafted.db")` file (no trigger, no view) for
  any reason — the helper is not producing a file shaped like a real backup; report the error.
- The fix seems to need a change to `loadSession`, the PWA, the backup format, or a migration.

## Maintenance notes

- **Why `loadSession` is not pinned to the configured origin** (the audit suggested it as a
  second lock): it has about thirty callers and the sync test suites (`SyncLifecycleTest`,
  `FamilyBudgetSyncTest`, `FamilyExclusionSyncTest`, `SyncRecoveryTest`, `EditsTest`) save
  sessions against `http://127.0.0.1:<port>` and reach `loadSession` through `syncNow`, so a
  pin there means threading an `allowedBase` through all of them. After this plan every way a
  session is written is already guarded: `claimHousehold` is called with `BuildConfig.SYNC_URL`,
  the join paths go through `parsePairingLink`'s origin check (plan 004), and a restore now
  brings none. If a fourth way to write `sync_base` is ever added, guard it there.
- A new `durable_meta` key that belongs to the session must be added to `BACKUP_STRIPPED_META`
  (and `ExportTest`'s pinned list); import then drops it with no further change.
- If the schema ever gains a real trigger or view (an FTS table, a `@DatabaseView`), the
  refusal in Step 1 must learn its name — the existing restore tests will fail loudly the day
  that happens.
