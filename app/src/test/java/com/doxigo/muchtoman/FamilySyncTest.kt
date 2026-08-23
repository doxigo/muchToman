package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilySyncTest {
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

}
