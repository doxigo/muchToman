package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class FamilySyncTest {

    private fun txn(
        ref: String,
        bank: String,
        sourceKind: String,
        familyRef: String = "",
    ): Txn = Txn(
        ref = ref, srcHash = ref, seq = 0, at = 1_700_000_000_000L, day = 20_000L,
        bank = bank, accountId = bank, direction = "out",
        amountRial = 2_500_000L, signedRial = -2_500_000L,
        balanceRial = null, feeRial = null, mask = "", instrument = "unknown",
        merchant = "", merchantNorm = "", refNo = "", printedAt = "",
        channel = "unknown", unitPrinted = "none", inferred = false,
        parserVer = PARSER_VERSION, sourceKind = sourceKind, familyRef = familyRef,
    )
    @Test
    fun `legacy short device identity is replaced before server registration`() {
        assertFalse(isValidSyncIdentity("a1b2c3d4"))
        assertTrue(isValidSyncIdentity("0123456789abcdef0123456789abcdef"))
        assertFalse(isValidSyncIdentity(null))
    }

    @Test
    fun `family transaction ids keep person and local transaction distinct`() {
        val member = "0123456789abcdef0123456789abcdef"
        val localRef = "s:${"ab".repeat(32)}:0"
        val familyRef = familyTxnId(member, localRef)

        assertEquals(member, ownerOfFamilyTxnId(familyRef))
        assertEquals(localRef, localRefOfFamilyTxn(familyRef, member))
        assertNull(localRefOfFamilyTxn(familyRef, "fedcba9876543210fedcba9876543210"))
        assertTrue(familyTxnId("fedcba9876543210fedcba9876543210", localRef) != familyRef)
    }

    @Test
    fun `remote family row preserves owner source and signed amount`() {
        val shared = FamilyTxn(
            id = "txn:member-a:736f75726365",
            ownerMemberId = "member-a",
            sourceKind = "sms",
            at = 1_700_000_000_000,
            day = 19_675,
            amountRial = -1_250_000,
            bank = "SAMAN",
            merchant = "فروشگاه",
            updatedAt = 1_700_000_000_100,
        )

        val row = familyToRow(shared)

        assertEquals(shared.id, row.familyRef)
        assertEquals("member-a", row.ownerMemberId)
        assertEquals("sms", row.sourceKind)
        assertEquals(-1_250_000L, row.signedRial)
        assertEquals(1_250_000L, row.amountRial)
        assertEquals("family:member-a:SAMAN", row.accountId)
    }

    /**
     * A note is words, and words about a row the family cannot see are the leak this gate exists
     * to stop. It is the same gate the transaction itself rides, which is the point: the answer
     * to «may this record leave» must not depend on which kind of answer it carries.
     */
    @Test
    fun `an answer about a row the family cannot see never leaves the phone`() {
        val excluded = setOf("MELLAT")
        val her = "member-a"
        fun decision(ref: String, familyRef: String = "", author: String = her) =
            TxnDecision(
                id = "d1",
                ref = ref,
                kind = DecisionKind.NOTE,
                value = "کادوی تولد مامان",
                createdAt = 1L,
                updatedAt = 1L,
                memberId = author,
                familyRef = familyRef,
            )

        val parsed = txn("s:aa:0", bank = "SAMAN", sourceKind = "sms")
        val typed = txn("m:bb", bank = "MANUAL", sourceKind = "manual")
        val setAside = txn("s:cc:0", bank = "MELLAT", sourceKind = "sms")
        val hers = txn("f:dd", bank = "SAMAN", sourceKind = "sms", familyRef = "txn:member-a:6161")

        // SMS sharing off: a note on a parsed message stays home, and so does a note on a row
        // whose transaction this phone has not even loaded but whose reference says «message».
        assertFalse(decisionMayLeave(decision("s:aa:0"), parsed, false, her, excluded))
        assertFalse(decisionMayLeave(decision("s:aa:0"), null, false, her, excluded))
        assertTrue(decisionMayLeave(decision("s:aa:0"), parsed, true, her, excluded))

        // A row she typed is hers to share whatever the SMS switch says.
        assertTrue(decisionMayLeave(decision("m:bb"), typed, false, her, excluded))

        // A bank she set aside keeps its rows home, notes about them included.
        assertFalse(decisionMayLeave(decision("s:cc:0"), setAside, true, her, excluded))

        // Her husband's row is already shared by him. Her note on it is hers to send.
        assertTrue(
            decisionMayLeave(decision("f:dd", familyRef = hers.familyRef), hers, false, her, excluded)
        )

        // His note on his own row reached her as his record and stays his. Echoing it back would
        // rewrite it under her name, and the byline on it is the whole point of a shared note.
        assertFalse(
            decisionMayLeave(
                decision("f:dd", familyRef = hers.familyRef, author = "member-b"),
                hers,
                true,
                her,
                excluded,
            )
        )

        // A note from before the household existed has no author, and that one is hers.
        assertTrue(decisionMayLeave(decision("m:bb", author = ""), typed, false, her, excluded))
    }

    @Test
    fun `a note keeps the line she typed and loses everything that is not text`() {
        assertEquals("قسط لپ‌تاپ\nماه دوم", safeSyncedNote("قسط لپ‌تاپ\nماه دوم"))
        assertEquals("قسط لپ‌تاپ", safeSyncedNote("  \u0000قسط لپ‌تاپ\r  "))
        assertEquals("", safeSyncedNote("\u0000\u0007"))
        assertEquals(MAX_NOTE_CHARS, safeSyncedNote("ا".repeat(MAX_NOTE_CHARS + 40)).length)
    }

    /**
     * Two people writing on the same row in the same instant. One of them wins, and both phones
     * have to agree on which — see [syncedEditWins].
     */
    @Test
    fun `the later note wins, and a tie is broken the same way on both phones`() {
        assertTrue(syncedEditWins(null, "", 0L, "member-a"))
        assertTrue(syncedEditWins(100L, "member-a", 101L, "member-b"))
        assertFalse(syncedEditWins(101L, "member-a", 100L, "member-b"))

        // The same millisecond, from both sides: exactly one of the two answers is «yes».
        assertTrue(syncedEditWins(100L, "member-a", 100L, "member-b"))
        assertFalse(syncedEditWins(100L, "member-b", 100L, "member-a"))

        // And a note arriving back at the phone that wrote it changes nothing.
        assertFalse(syncedEditWins(100L, "member-a", 100L, "member-a"))
    }
    @Test
    fun `automatic category refresh cannot overwrite a member edit`() {
        assertTrue(categoryUpdateWins(0L, "owner", 0L, "owner"))
        assertFalse(categoryUpdateWins(100L, "member-b", 0L, "owner"))
        assertTrue(categoryUpdateWins(100L, "member-a", 101L, "member-b"))
        assertTrue(categoryUpdateWins(100L, "member-a", 100L, "member-b"))
        assertFalse(categoryUpdateWins(100L, "member-b", 100L, "member-a"))
    }

    @Test
    fun `legacy payload cannot claim another authenticated owner`() {
        assertEquals("member-a", resolvedTransactionOwner("legacy", "member-b", "member-a"))
        assertEquals("member-b", resolvedTransactionOwner("transaction", "member-b", "member-a"))
    }

    @Test
    fun `a delete is only believed when the sealed body says so and names its record`() {
        // The plaintext here stands for a body that already authenticated under the scope key;
        // what these pin down is the parse: which sealed sentences count as a delete at all.
        val good = parseTombstone("""{"v":1,"id":"txn:member-a:736f75726365","deleted":true}""")
        assertEquals("txn:member-a:736f75726365", good?.id)

        // The pre-release contentless shape carried no id to bind, so a compromised server could
        // re-attach a real tombstone to any record. Rejected outright — nothing shipped it.
        assertNull(parseTombstone("""{"kind":"tombstone"}"""))
        assertNull(parseTombstone("""{"v":1,"id":"txn:member-a:736f75726365","deleted":false}"""))
        assertNull(parseTombstone("""{"v":1,"id":"","deleted":true}"""))
        assertNull(parseTombstone("""{"v":1,"deleted":true}"""))
        assertNull(parseTombstone("not json"))
    }

    @Test
    fun `a scanned link is a join, a shrug or a replace depending on the household it names`() {
        val hid = "aabbccddeeff00112233445566778899"
        val other = "ffeeddccbbaa99887766554433221100"
        val token = "$hid.some-server-secret"

        // No session: the ordinary join, no question to ask.
        assertEquals(PairingCase.JOIN, pairingCase(null, hid))
        assertEquals(PairingCase.JOIN, pairingCase(null, other))

        // Her own household's QR: nothing to join and nothing to replace.
        assertEquals(PairingCase.SAME_HOUSEHOLD, pairingCase(token, hid))

        // A different household: only ever behind the confirmed replace.
        assertEquals(PairingCase.REJOIN, pairingCase(token, other))

        // A malformed stored token still cannot make its own household look foreign.
        assertEquals(PairingCase.SAME_HOUSEHOLD, pairingCase(hid, hid))
    }

    @Test
    fun `stamps from the far future are clamped so one skewed clock cannot pin a record`() {
        val now = 1_700_000_000_000L
        val horizon = now + MAX_SYNC_STAMP_SKEW_MS
        assertEquals(now, clampSyncStamp(now, now))
        assertEquals(now - 1, clampSyncStamp(now - 1, now))
        assertEquals(horizon, clampSyncStamp(horizon, now))
        assertEquals(horizon, clampSyncStamp(horizon + 1, now))
        assertEquals(horizon, clampSyncStamp(Long.MAX_VALUE, now))
    }

    @Test
    fun `asset share prices with own rates and honours every veto`() {
        val holdings = listOf(
            Holding("usd", 100.0),
            Holding("gold_18", 2.0, excluded = true), // set aside → stays home
            Holding("mystery", 5.0), // no rate → left out, never shared as zero
            Holding("car", 1_000.0, label = "ماشین من"), // Toman by definition, her own name
        )
        val accounts = listOf(
            BankAccount("SAMAN", balance = 500.0, anchored = true),
            BankAccount("BLU", balance = 200.0, anchored = true),
            BankAccount("MELLI", balance = 900.0, anchored = false), // a guess, not a balance
        )
        val items = assetShareItems(
            holdings = holdings,
            rates = mapOf("usd" to 60.0, "gold_18" to 5_000.0) + TOMAN_BY_DEFINITION,
            coins = emptyList(),
            stocks = emptyList(),
            smsEnabled = true,
            bankAccounts = accounts,
            disabledBanks = emptySet(),
            familyExcluded = setOf("BLU"),
        )

        assertEquals(3, items.size)
        assertTrue(items.any { it.name == "ماشین من" && it.toman == 1_000.0 })
        assertTrue(items.any { it.toman == 6_000.0 }) // usd at this phone's rate
        assertTrue(items.any { it.toman == 500.0 }) // the one bank that cleared both vetoes
        assertTrue(items.none { it.toman == 200.0 }) // BLU, kept out of the family
        assertEquals(7_500.0, items.sumOf { it.toman }, 0.001)

        // No messages read means no bank rows to speak of, whatever the accounts list says.
        val noSms = assetShareItems(
            holdings = holdings,
            rates = mapOf("usd" to 60.0) + TOMAN_BY_DEFINITION,
            coins = emptyList(),
            stocks = emptyList(),
            smsEnabled = false,
            bankAccounts = accounts,
            disabledBanks = emptySet(),
            familyExcluded = emptySet(),
        )
        assertTrue(noSms.none { it.toman == 500.0 })
    }

    @Test
    fun `asset sharing drops non finite and overflowing values`() {
        val items = assetShareItems(
            holdings = listOf(
                Holding("overflow", Double.MAX_VALUE),
                Holding("valid", 2.0),
            ),
            rates = mapOf("overflow" to 2.0, "valid" to 50.0),
            coins = emptyList(),
            stocks = emptyList(),
            smsEnabled = false,
            bankAccounts = emptyList(),
            disabledBanks = emptySet(),
            familyExcluded = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(100.0, items.single().toman, 0.0)
        assertTrue(items.all { it.toman.isFinite() })

        val bounded = safeAssetShareItems(
            listOf(
                AssetShareItem("bad", Double.POSITIVE_INFINITY),
                AssetShareItem("negative", -1.0),
                AssetShareItem("first", MAX_PLAUSIBLE_RIAL.toDouble()),
                AssetShareItem("would overflow total", 1.0),
            )
        )
        assertEquals(listOf("first"), bounded.map { it.name })
    }

    @Test
    fun `an arriving face is held to the shapes this build renders`() {
        // The two stock faces and a small photo pass through untouched.
        assertEquals(AVATAR_MAN, safeSyncedAvatar(AVATAR_MAN))
        assertEquals(AVATAR_WOMAN, safeSyncedAvatar(AVATAR_WOMAN))
        val photo = AVATAR_PHOTO_PREFIX + "a".repeat(8_000)
        assertEquals(photo, safeSyncedAvatar(photo))

        // A photo blown past the cap lands blank — blank is the initial, never wrong.
        assertEquals("", safeSyncedAvatar(AVATAR_PHOTO_PREFIX + "a".repeat(AVATAR_B64_MAX + 1)))

        // A non-photo string is bounded like every other synced text.
        assertEquals("", safeSyncedAvatar(" "))
        assertEquals(16, safeSyncedAvatar("x".repeat(500)).length)
    }

    @Test
    fun `excluded banks survive the round trip through meta`() {
        assertEquals(setOf("SAMAN", "BLU"), parseExcludedBanks(setOf("SAMAN", "BLU").joinToString(",")))
        assertEquals(emptySet<String>(), parseExcludedBanks(""))
        assertEquals(emptySet<String>(), parseExcludedBanks(null))
    }
}
