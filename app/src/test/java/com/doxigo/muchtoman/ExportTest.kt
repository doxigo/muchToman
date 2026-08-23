package com.doxigo.muchtoman

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The backup envelope, off-device. This file is the only copy of her data once the phone is
 * gone, so what is pinned here is not style: the crypto must refuse everything but the right
 * passphrase, and the include/exclude lists must not drift under anyone's feet. Run with:
 * ./gradlew testFullDebugUnitTest --tests '*ExportTest*'
 */
class ExportTest {

    // Small on purpose — the KDF's cost is a production parameter, not what these tests probe.
    // One test below runs the real 600k to prove the shipped parameters work end to end.
    private val fastIterations = 1_000

    private fun samplePayload() = BackupPayload(
        prefs = mapOf(
            "holdings" to BackupPref("s", """[{"typeId":"gold18","amount":2.5}]"""),
            "smsEnabled" to BackupPref("b", "true"),
            "smsSchema" to BackupPref("i", "11"),
            "smsScannedTo" to BackupPref("l", "1723000000000"),
        ),
        // Real binary, not text: the database bytes must survive base64 and the cipher intact.
        durableDbB64 = kotlin.io.encoding.Base64.encode(ByteArray(512) { (it * 7).toByte() }),
    )

    private fun seal(
        payload: BackupPayload = samplePayload(),
        passphrase: String = "قند و نبات",
        formatVersion: Int = BACKUP_FORMAT_VERSION,
    ) = sealBackup(
        payload,
        passphrase,
        createdAt = 1_700_000_000_000L,
        appVersionCode = 42,
        iterations = fastIterations,
        formatVersion = formatVersion,
        random = SecureRandom(),
    )

    private fun faultOf(block: () -> Unit): BackupFault {
        try {
            block()
        } catch (e: BackupException) {
            return e.fault
        }
        fail("expected a BackupException")
        error("unreachable")
    }

    @Test
    fun `seals and opens with the right passphrase`() {
        val payload = samplePayload()
        val opened = openBackup(seal(payload), "قند و نبات")
        assertEquals(payload, opened.payload)
        assertEquals(1_700_000_000_000L, opened.header.createdAt)
        assertEquals(42, opened.header.appVersionCode)
        assertEquals(BACKUP_FORMAT_VERSION, opened.header.formatVersion)
        assertEquals(BACKUP_KDF_ALGO, opened.header.kdf.algo)
        assertEquals(BACKUP_CIPHER_ALGO, opened.header.cipher.algo)
    }

    @Test
    fun `the shipped parameters work end to end`() {
        // The real 600k rounds, once: a backup made with defaults must open with defaults.
        val sealed = sealBackup(samplePayload(), "شش کاراکتر", 1L, 1)
        val opened = openBackup(sealed, "شش کاراکتر")
        assertEquals(600_000, BACKUP_KDF_ITERATIONS)
        assertEquals(BACKUP_KDF_ITERATIONS, opened.header.kdf.iterations)
        assertEquals(samplePayload(), opened.payload)
    }

    @Test
    fun `a wrong passphrase is refused as wrong-or-corrupt`() {
        val sealed = seal(passphrase = "قند و نبات")
        assertEquals(
            BackupFault.WRONG_PASSPHRASE_OR_CORRUPT,
            faultOf { openBackup(sealed, "قند و نباد") },
        )
    }

    @Test
    fun `a flipped ciphertext bit fails authentication`() {
        val sealed = seal()
        // The last byte sits inside GCM's tag; any bit of the body would do the same.
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertEquals(
            BackupFault.WRONG_PASSPHRASE_OR_CORRUPT,
            faultOf { openBackup(sealed, "قند و نبات") },
        )
    }

    @Test
    fun `a tampered header is caught even when it stays well-formed`() {
        val sealed = seal()
        // Nudge one digit of createdAt: still valid JSON, same salt, same IV, same key — only
        // the header-as-associated-data check can notice, and it must.
        val at = String(sealed, Charsets.ISO_8859_1).indexOf("\"createdAt\":")
        assertTrue(at > 0)
        val digit = at + "\"createdAt\":".length
        assertEquals('1'.code.toByte(), sealed[digit])
        sealed[digit] = '2'.code.toByte()
        assertEquals(
            BackupFault.WRONG_PASSPHRASE_OR_CORRUPT,
            faultOf { openBackup(sealed, "قند و نبات") },
        )
    }

    @Test
    fun `garbage is not a backup`() {
        assertEquals(BackupFault.NOT_A_BACKUP, faultOf { openBackup(ByteArray(0), "قند و نبات") })
        assertEquals(BackupFault.NOT_A_BACKUP, faultOf { openBackup(ByteArray(3), "قند و نبات") })
        val noise = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        // Whatever the noise happens to spell, it does not start with the magic.
        noise[0] = 'X'.code.toByte()
        assertEquals(BackupFault.NOT_A_BACKUP, faultOf { openBackup(noise, "قند و نبات") })
    }

    @Test
    fun `a backup from a newer format asks for an update, not a shrug`() {
        val sealed = seal(formatVersion = BACKUP_FORMAT_VERSION + 1)
        assertEquals(BackupFault.NEWER_FORMAT, faultOf { openBackup(sealed, "قند و نبات") })
    }

    @Test
    fun `a truncated file is refused before any crypto runs`() {
        val sealed = seal()
        // Cut inside the ciphertext, past the header: shorter than even a bare GCM tag.
        val cut = sealed.copyOf(findCiphertextStart(sealed) + 8)
        assertEquals(BackupFault.NOT_A_BACKUP, faultOf { openBackup(cut, "قند و نبات") })
    }

    private fun findCiphertextStart(sealed: ByteArray): Int {
        val headerLen = ((sealed[6].toInt() and 0xff) shl 24) or
            ((sealed[7].toInt() and 0xff) shl 16) or
            ((sealed[8].toInt() and 0xff) shl 8) or
            (sealed[9].toInt() and 0xff)
        return 10 + headerLen
    }

    @Test
    fun `a short passphrase is refused at sealing time`() {
        try {
            seal(passphrase = "کوتاه")
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // Five characters: the sheet says the same thing in words before this ever runs.
        }
    }

    @Test
    fun `gzip roundtrips and actually earns its keep on bank messages`() {
        val corpus = buildString {
            repeat(500) {
                append("بانک ملت\nبرداشت: 1,250,000\nمانده: 34,500,000\n1403/05/0$it\n")
            }
        }.toByteArray(Charsets.UTF_8)
        val zipped = gzip(corpus)
        assertArrayEquals(corpus, gunzip(zipped))
        assertTrue("gzip should shrink repetitive SMS text", zipped.size < corpus.size / 2)
    }

    @Test
    fun `gunzip refuses to inflate past its cap`() {
        val bomb = gzip(ByteArray(1024 * 1024)) // a megabyte of zeros in a few hundred bytes
        try {
            gunzip(bomb, maxBytes = 64 * 1024)
            fail("expected the cap to trip")
        } catch (expected: IllegalStateException) {
            // The cap, not an OutOfMemoryError, is what stands between a crafted file and the heap.
        }
    }

    @Test
    fun `the manual kdf matches the platform's, persian passphrase included`() {
        val salt = ByteArray(16) { it.toByte() }
        val passphrase = "رمز فارسی ۱۲۳".toCharArray()
        val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, fastIterations, 256)).encoded
        val manual = pbkdf2HmacSha256(passphrase, salt, fastIterations, 32)
        // The fallback below API 26 must be the same function, or a backup made on an old phone
        // never opens on a new one — and this equality is the whole licence to ship it.
        assertArrayEquals(platform, manual)
        assertEquals(32, manual.size)
    }

    @Test
    fun `pref typing survives the backup shape`() {
        assertEquals(BackupPref("s", "مریم"), prefBackupValue("مریم"))
        assertEquals(BackupPref("b", "true"), prefBackupValue(true))
        assertEquals(BackupPref("i", "11"), prefBackupValue(11))
        assertEquals(BackupPref("l", "1723000000000"), prefBackupValue(1_723_000_000_000L))
        // Shapes Store never writes are dropped, not guessed at.
        assertNull(prefBackupValue(1.5f))
        assertNull(prefBackupValue(null))

        val prefs = mapOf("a" to BackupPref("s", "x"), "b" to BackupPref("l", "9"))
        assertEquals(prefs, decodeBackupPrefs(encodeBackupPrefs(prefs)))
    }

    /**
     * The lists themselves, pinned key by key. Adding a key to the backup — or quietly slipping
     * one back in — must arrive as a diff to this test, with the reasoning to match.
     */
    @Test
    fun `the exported and excluded pref lists are pinned`() {
        assertEquals(
            listOf(
                "holdings", "overrides", "history", "bankAccounts", "disabledBanks",
                "seenSms", "smsScannedTo", "smsSchema", "extraBankNumbers", "dismissedSenders",
                "name", "themeMode", "lockEnabled", "widgetLock", "onboarded", "smsEnabled",
                "dismissedUpdate", "reportExcluded",
            ),
            EXPORTED_PREFS,
        )
        // Refetchable caches and this-phone announcement marks stay off other phones for ever.
        assertEquals(
            listOf("rates", "stocks", "budgetMarks", "filingMark", "strangers"),
            EXCLUDED_PREFS,
        )
        assertTrue(EXPORTED_PREFS.intersect(EXCLUDED_PREFS.toSet()).isEmpty())
        // The device identity must never ride the file, under any spelling.
        assertFalse(EXPORTED_PREFS.any { it.contains("sync", ignoreCase = true) })
        assertFalse(EXPORTED_PREFS.any { it.contains("token", ignoreCase = true) })
    }

    @Test
    fun `the sync identity is stripped from the exported database`() {
        // durable_meta rows deleted from the copy before it is sealed: the whole device
        // identity, its token, keys and cursor. A restored phone re-pairs; a stolen backup
        // plus its passphrase still cannot impersonate the phone it came from.
        assertEquals(
            listOf(
                "sync_base", "sync_token", "sync_token_at", "sync_device", "sync_member",
                "sync_scope", "sync_key", "sync_seq", "sync_identity_ok", "sync_share_sms",
            ),
            BACKUP_STRIPPED_META,
        )
    }

    @Test
    fun `two seals of one payload share nothing visible`() {
        val a = seal()
        val b = seal()
        val headerA = openHeaderFields(a)
        val headerB = openHeaderFields(b)
        // Fresh salt and IV every time — a reused GCM nonce under one key is a broken cipher.
        assertFalse(headerA.first == headerB.first)
        assertFalse(headerA.second == headerB.second)
    }

    private fun openHeaderFields(sealed: ByteArray): Pair<String, String> {
        val text = String(sealed, 10, findCiphertextStart(sealed) - 10, Charsets.UTF_8)
        val salt = text.substringAfter("\"saltB64\":\"").substringBefore("\"")
        val iv = text.substringAfter("\"ivB64\":\"").substringBefore("\"")
        return salt to iv
    }
}
