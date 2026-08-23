package com.doxigo.muchtoman

import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val GCM_TAG_BITS = 128
private const val NONCE_BYTES = 12
private val SYNC_RANDOM by lazy { SecureRandom() }
private val SYNC_IDENTITY = Regex("[a-f0-9]{16,64}")

private val SYNC_JSON = Json { ignoreUnknownKeys = true }

const val META_SYNC_BASE = "sync_base"
const val META_SYNC_TOKEN = "sync_token"
const val META_SYNC_DEVICE = "sync_device"
const val META_SYNC_MEMBER = "sync_member"
const val META_SYNC_SCOPE = "sync_scope"
const val META_SYNC_KEY = "sync_key"
const val META_SYNC_SEQ = "sync_seq"
const val META_SYNC_SHARE_SMS = "sync_share_sms"
const val META_SYNC_IDENTITY_OK = "sync_identity_ok"

data class SyncSession(
    val base: String,
    val token: String,
    val device: String,
    val member: String,
    val scope: String,
    val key: ByteArray,
) {
    override fun equals(other: Any?) = other is SyncSession && token == other.token && member == other.member
    override fun hashCode() = 31 * token.hashCode() + member.hashCode()
}

private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

fun b64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

private fun unb64Url(value: String): ByteArray =
    Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

fun newScopeKey(): ByteArray = ByteArray(32).also { SYNC_RANDOM.nextBytes(it) }

fun seal(key: ByteArray, plaintext: String): Pair<String, String> {
    val nonce = ByteArray(NONCE_BYTES).also { SYNC_RANDOM.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return b64(nonce) to b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
}

fun openSealed(key: ByteArray, nonce: String, body: String): String? = runCatching {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(GCM_TAG_BITS, unb64(nonce)),
    )
    String(cipher.doFinal(unb64(body)), Charsets.UTF_8)
}.getOrNull()

@Serializable
data class SyncEntry(
    val kind: String = "transaction",
    val ownerMemberId: String = "",
    val sourceKind: String = "manual",
    val at: Long,
    val amountRial: Long,
    val direction: String,
    val bank: String = "MANUAL",
    val categoryId: String = "",
    val categoryName: String = "",
    val categoryKind: String = CategoryKind.EXPENSE,
    /** The mark, for a category she made. Blank on shipped ones, which every build looks up. */
    val categoryGlyph: String = "",
    val categoryEditorId: String = "",
    val categoryUpdatedAt: Long = 0L,
    /** Compatibility with records written before category ids were synchronized. */
    val category: String = "",
    val merchant: String = "",
    val note: String = "",
)

@Serializable
private data class SyncMemberPayload(
    val kind: String = "member",
    val memberId: String,
    val name: String,
    val sharesSms: Boolean,
)

@Serializable
private data class SyncCategoryPayload(
    val kind: String = "category",
    val target: String,
    val categoryId: String,
    val categoryName: String,
    val categoryKind: String,
    val categoryGlyph: String = "",
    val editedByMemberId: String,
)

@Serializable
private data class SyncTombstonePayload(val kind: String = "tombstone")

@Serializable
private data class WireRecord(
    val id: String,
    val scope: String,
    val updatedAt: Long,
    val device: String,
    val kind: String = "legacy",
    val ownerMemberId: String = "",
    val authorMemberId: String = "",
    val deleted: Boolean = false,
    val nonce: String,
    val body: String,
)

@Serializable
private data class PushBody(val records: List<WireRecord>)

@Serializable
private data class PullBody(val seq: Long = 0, val records: List<WireRecord> = emptyList())

@Serializable
private data class ClaimBody(
    val scopes: List<String>,
    val memberId: String,
    val deviceId: String,
)

@Serializable
private data class InviteBody(val scopes: List<String>)

@Serializable
private data class PairBody(val code: String, val memberId: String, val deviceId: String)

@Serializable
private data class IdentityBody(val memberId: String, val deviceId: String)

@Serializable
private data class SecretBody(
    val secret: String = "",
    val memberId: String = "",
    val deviceId: String = "",
)

@Serializable
private data class CodeBody(val code: String = "")

private data class RevokeBody(val member: String)
private fun request(
    url: String,
    method: String,
    token: String?,
    payload: String?,
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        readTimeout = 15_000
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
        if (payload != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    return connection.use { conn ->
        payload?.let { conn.outputStream.use { out -> out.write(it.toByteArray(Charsets.UTF_8)) } }
        if (conn.responseCode !in 200..299) {
            val detail = conn.errorStream?.readUtf8Limited(4096).orEmpty()
            throw IllegalStateException("sync ${conn.responseCode}: ${detail.take(120)}")
        }
        conn.inputStream.readUtf8Limited(1 shl 20)
    }
}

private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
    try { block(this) } finally { disconnect() }

private fun newIdentity(): String = uuid7().replace("-", "")

internal fun isValidSyncIdentity(value: String?): Boolean =
    value != null && SYNC_IDENTITY.matches(value)

private fun cleanMemberName(value: String): String =
    value.filterNot(Char::isISOControl).trim().take(32).ifBlank { "عضو خانواده" }

private fun bytesHex(value: String): String = hexOf(value.toByteArray(Charsets.UTF_8))

private fun hexText(value: String): String? {
    if (value.length % 2 != 0 || value.any { it !in "0123456789abcdef" }) return null
    return runCatching {
        val bytes = ByteArray(value.length / 2)
        for (i in bytes.indices) bytes[i] = value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        String(bytes, Charsets.UTF_8)
    }.getOrNull()
}

fun familyTxnId(memberId: String, localRef: String): String = "txn:$memberId:${bytesHex(localRef)}"

fun ownerOfFamilyTxnId(familyRef: String): String? =
    familyRef.takeIf { it.startsWith("txn:") }?.split(':', limit = 3)?.getOrNull(1)?.takeIf(String::isNotBlank)

fun localRefOfFamilyTxn(familyRef: String, memberId: String): String? {
    val parts = familyRef.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != "txn" || parts[1] != memberId) return null
    return hexText(parts[2])
}

private fun memberRecordId(memberId: String): String = "member:$memberId"
private fun categoryRecordId(familyRef: String): String = "category:${sha256Hex(familyRef)}"

private fun claimFreshHousehold(base: String, member: String, device: String): SyncSession {
    val hid = hexOf(ByteArray(16).also { SYNC_RANDOM.nextBytes(it) })
    val scope = "family:$hid"
    val response = request(
        "${base.trimEnd('/')}/v1/claim?hid=$hid",
        "POST",
        null,
        SYNC_JSON.encodeToString(ClaimBody(listOf(scope), member, device)),
    )
    val secret = SYNC_JSON.decodeFromString<SecretBody>(response).secret
    return SyncSession(
        base = base.trimEnd('/'),
        token = "$hid.$secret",
        device = device,
        member = member,
        scope = scope,
        key = newScopeKey(),
    )
}

suspend fun claimHousehold(base: String, durable: DurableDb, memberName: String): SyncSession =
    withContext(Dispatchers.IO) {
        val hid = hexOf(ByteArray(16).also { SYNC_RANDOM.nextBytes(it) })
        val session = claimFreshHousehold(base, newIdentity(), newIdentity())
        saveSession(durable, session)
        durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, "false"))
        durable.familyMembers().put(
            FamilyMember(member, cleanMemberName(memberName), sharesSms = false, updatedAt = System.currentTimeMillis())
        )
        session
    }

suspend fun saveSession(durable: DurableDb, session: SyncSession) {
    durable.meta().put(DurableMeta(META_SYNC_BASE, session.base))
    durable.meta().put(DurableMeta(META_SYNC_TOKEN, session.token))
    durable.meta().put(DurableMeta(META_SYNC_DEVICE, session.device))
    durable.meta().put(DurableMeta(META_SYNC_MEMBER, session.member))
    durable.meta().put(DurableMeta(META_SYNC_SCOPE, session.scope))
    durable.meta().put(DurableMeta(META_SYNC_KEY, b64(session.key)))
}

suspend fun loadSession(durable: DurableDb): SyncSession? {
    val base = durable.meta().get(META_SYNC_BASE) ?: return null
    val token = durable.meta().get(META_SYNC_TOKEN) ?: return null
    val storedDevice = durable.meta().get(META_SYNC_DEVICE) ?: return null
    val device = storedDevice.takeIf(::isValidSyncIdentity) ?: newIdentity().also {
        durable.meta().put(DurableMeta(META_SYNC_DEVICE, it))
    }
    val member = durable.meta().get(META_SYNC_MEMBER)?.takeIf(::isValidSyncIdentity) ?: newIdentity().also {
        durable.meta().put(DurableMeta(META_SYNC_MEMBER, it))
    }
    val scope = durable.meta().get(META_SYNC_SCOPE) ?: return null
    val key = durable.meta().get(META_SYNC_KEY)?.let(::unb64) ?: return null
    return SyncSession(base, token, device, member, scope, key)
}

private suspend fun registerIdentity(session: SyncSession, durable: DurableDb) {
    if (durable.meta().get(META_SYNC_IDENTITY_OK) == "true") return
    request(
        "${session.base}/v1/identity",
        "POST",
        session.token,
        SYNC_JSON.encodeToString(IdentityBody(session.member, session.device)),
    )
    durable.meta().put(DurableMeta(META_SYNC_IDENTITY_OK, "true"))
}

suspend fun invite(session: SyncSession, durable: DurableDb): String = withContext(Dispatchers.IO) {
    registerIdentity(session, durable)
    val response = request(
        "${session.base}/v1/invite",
        "POST",
        session.token,
        SYNC_JSON.encodeToString(InviteBody(listOf(session.scope))),
    )
    SYNC_JSON.decodeFromString<CodeBody>(response).code
}

fun pairingUrl(session: SyncSession, code: String): String {
    val hid = session.token.substringBefore('.')
    return "${session.base}/join#url=${Uri.encode(session.base)}&hid=$hid&pair=$code" +
        "&scope=${Uri.encode(session.scope)}&k=${b64Url(session.key)}"
}

data class PairingInvite(
    val base: String,
    val hid: String,
    val code: String,
    val scope: String,
    val key: ByteArray,
)

fun parsePairingLink(value: String): PairingInvite? = runCatching {
    val uri = Uri.parse(value)
    val fragment = uri.encodedFragment ?: return null
    val params = Uri.parse("https://pairing.local/?$fragment")
    val base = params.getQueryParameter("url")?.trimEnd('/') ?: return null
    val hid = params.getQueryParameter("hid") ?: return null
    val code = params.getQueryParameter("pair") ?: return null
    val scope = params.getQueryParameter("scope") ?: return null
    val key = params.getQueryParameter("k")?.let(::unb64Url) ?: return null
    val baseUrl = URL(base)
    if (baseUrl.protocol !in setOf("https", "http") || hid.length !in 16..64 || key.size != 32) return null
    PairingInvite(base, hid, code, scope, key)
}.getOrNull()

/** The network half of a join: redeem the one-time code for a session. Persists nothing. */
private fun pairHousehold(link: String): SyncSession {
    val pairing = parsePairingLink(link) ?: error("invalid pairing link")
    val member = newIdentity()
    val device = newIdentity()
    val response = request(
        "${pairing.base}/v1/pair",
        "POST",
        "${pairing.hid}.${"0".repeat(64)}",
        SYNC_JSON.encodeToString(PairBody(pairing.code, member, device)),
    )
    val secret = SYNC_JSON.decodeFromString<SecretBody>(response).secret
    return SyncSession(
        base = pairing.base,
        token = "${pairing.hid}.$secret",
        device = device,
        member = member,
        scope = pairing.scope,
        key = pairing.key,
    )
}

/** The local half: the session becomes this phone's household, private until she says otherwise. */
private suspend fun commitJoin(durable: DurableDb, session: SyncSession, memberName: String) {
    saveSession(durable, session)
    durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, "false"))
    durable.familyMembers().put(
        FamilyMember(session.member, cleanMemberName(memberName), sharesSms = false, updatedAt = System.currentTimeMillis())
    )
}

suspend fun joinHousehold(link: String, durable: DurableDb, memberName: String): SyncSession =
    withContext(Dispatchers.IO) {
        val session = pairHousehold(link)
        commitJoin(durable, session, memberName)
        session
    }

/**
 * What a scanned pairing link means on this phone. Decided from the stored token rather than
 * any on-screen flag, because at a cold start through the link the state has not been read
 * yet — and the hid *is* the household: the same one names her own family's QR (nothing to do),
 * a different one asks to replace the household this phone is in.
 */
enum class PairingCase { JOIN, SAME_HOUSEHOLD, REJOIN }

fun pairingCase(sessionToken: String?, linkHid: String): PairingCase = when {
    sessionToken == null -> PairingCase.JOIN
    sessionToken.substringBefore('.') == linkHid -> PairingCase.SAME_HOUSEHOLD
    else -> PairingCase.REJOIN
}

/**
 * A confirmed replace: the same join, from a phone that already belongs somewhere. The network
 * pair runs first so a dead code costs nothing — the old household is untouched until the new
 * one has said yes — and then, in one transaction, the old household is buried exactly as
 * [renewHousehold] buries it and the new session is written. Nobody is kept: unlike a renewal,
 * the pair minted a fresh member id, so the old own row belongs to a household this phone left.
 */
suspend fun rejoinHousehold(link: String, durable: DurableDb, memberName: String): SyncSession =
    withContext(Dispatchers.IO) {
        val session = pairHousehold(link)
        durable.withTransaction {
            buryHousehold(durable, keepMember = null)
            commitJoin(durable, session, memberName)
        }
        session
    }
/**
 * Cuts a member's devices off the household, effective on their very next request.
 *
 * Three moves, in the order that fails safe. First the server forgets their tokens — the only
 * step that stops future sync, so it goes first. Then their local row is buried so the list
 * stops showing someone who is no longer there. Last, a sealed tombstone for their member record
 * goes up so the other phones learn the same thing; the server lets a non-owner push exactly this
 * one shape, and the receivers still verify the ciphertext names this very record.
 *
 * What this does not do, on purpose: it does not touch their past transactions here or anywhere,
 * because they were already seen — the same honesty as the privacy sentence on the screen. And it
 * does not re-key: an ex-member who somehow keeps reading ciphertext still holds the scope key.
 * [renewHousehold] is the answer to that.
 */
suspend fun removeFamilyMember(session: SyncSession, durable: DurableDb, memberId: String): Unit =
    withContext(Dispatchers.IO) {
        require(memberId != session.member) { "not for leaving" }
        request(
            "${session.base}/v1/revoke",
            "POST",
            session.token,
            SYNC_JSON.encodeToString(RevokeBody(member = memberId)),
        )
        val member = durable.familyMembers().get(memberId)
        val stamp = nextStamp(member?.updatedAt, System.currentTimeMillis())
        durable.familyMembers().put(
            (member ?: FamilyMember(memberId, "عضو خانواده", updatedAt = stamp))
                .copy(updatedAt = stamp, deleted = true)
        )
        val recordId = memberRecordId(memberId)
        val tombstone = wireRecord(
            session,
            recordId,
            "member",
            memberId,
            stamp,
            SYNC_JSON.encodeToString(SyncTombstonePayload(v = 1, id = recordId, deleted = true)),
            deleted = true,
        )
        request(
            "${session.base}/v1/sync",
            "POST",
            session.token,
            SYNC_JSON.encodeToString(PushBody(listOf(tombstone))),
        )
    }
suspend fun renewHousehold(durable: DurableDb): SyncSession = withContext(Dispatchers.IO) {
    val old = loadSession(durable) ?: error("no household to renew")
    val session = claimFreshHousehold(old.base, old.member, old.device)
    durable.withTransaction {
        saveSession(durable, session)
        // The device keeps its identity here, so its own member row rides into the new
        // household; everything else about the old one is buried.
        buryHousehold(durable, keepMember = session.member)
    }
    session
}
/**
 * Every local trace of the household this phone is leaving, buried in place — shared by
 * [renewHousehold] and [rejoinHousehold], whose only difference is whether the member walks
 * into the next household under the same identity. Callers run this inside the transaction
 * that writes the replacement session, so a crash can never leave half a household.
 */
private suspend fun buryHousehold(durable: DurableDb, keepMember: String?) {
    // The cursor and the identity registration belong to a server this device will never
    // speak to again; the publications are buried rather than deleted so the same rows keep
    // their monotonic stamps when they are re-published under the new key.
    durable.meta().put(DurableMeta(META_SYNC_SEQ, "0"))
    durable.meta().put(DurableMeta(META_SYNC_IDENTITY_OK, "false"))
    val publications = durable.syncPublications().all()
    if (publications.isNotEmpty()) {
        durable.syncPublications().putAll(publications.map { it.copy(deleted = true) })
    }
    val now = System.currentTimeMillis()
    for (member in durable.familyMembers().all()) {
        if (member.id == keepMember) continue
        durable.familyMembers().put(member.copy(updatedAt = nextStamp(member.updatedAt, now), deleted = true))
    }
    // Their rows would double the moment they rejoin and re-push under a fresh member id,
    // so the old copies go now, while the copy on screen is saying "از نو".
    for (txn in durable.familyTxns().all()) {
        durable.familyTxns().put(txn.copy(updatedAt = nextStamp(txn.updatedAt, now), deleted = true))
    }
}

data class SyncResult(val sent: Int, val received: Int)

private data class PreparedRecord(
    val wire: WireRecord,
    val publication: SyncPublication? = null,
)

private fun nextStamp(previous: Long?, now: Long): Long = maxOf(now, (previous ?: 0L) + 1L)

private fun wireRecord(
    session: SyncSession,
    id: String,
    kind: String,
    ownerMemberId: String,
    updatedAt: Long,
    payload: String,
    deleted: Boolean = false,
): WireRecord {
    val (nonce, body) = seal(session.key, payload)
    return WireRecord(
        id = id,
        scope = session.scope,
        updatedAt = updatedAt,
        device = session.device,
        kind = kind,
        ownerMemberId = ownerMemberId,
        nonce = nonce,
        body = body,
        deleted = deleted,
    )
}

private suspend fun outgoingRecords(
    durable: DurableDb,
    derived: DerivedDb,
    session: SyncSession,
    now: Long,
): List<PreparedRecord> {
    val shareSms = durable.meta().get(META_SYNC_SHARE_SMS).toBoolean()
    val ownMember = durable.familyMembers().get(session.member) ?: FamilyMember(
        id = session.member,
        name = "عضو خانواده",
        sharesSms = shareSms,
        updatedAt = now,
    ).also { durable.familyMembers().put(it) }
    val member = if (ownMember.sharesSms == shareSms) ownMember else ownMember.copy(
        sharesSms = shareSms,
        updatedAt = nextStamp(ownMember.updatedAt, now),
    ).also { durable.familyMembers().put(it) }

    val profilePayload = SYNC_JSON.encodeToString(
        SyncMemberPayload(memberId = member.id, name = member.name, sharesSms = member.sharesSms)
    )
    val outgoing = mutableListOf(
        PreparedRecord(
            wireRecord(
                session,
                memberRecordId(member.id),
                "member",
                member.id,
                member.updatedAt,
                profilePayload,
            )
        )
    )

    val ledger = ledgerEntries(derived, durable, limit = SOURCE_HARD_CAP)
    val categoryById = ledger.categories.associateBy { it.id }
    val categoryDecisions = ledger.categoryDecisions.values.toList()
    val categoryDecisionByRef = ledger.categoryDecisions
    val publications = durable.syncPublications().all().associateBy { it.id }
    val activeIds = mutableSetOf<String>()

    for (entry in ledger.entries) {
        val txn = entry.txn
        if (entry.duplicate || txn.familyRef.isNotBlank() || txn.ownerMemberId.isNotBlank()) continue
        val signed = txn.signedRial ?: continue
        val sourceKind = txn.sourceKind
        if (sourceKind == "sms" && !shareSms) continue
        val id = familyTxnId(session.member, txn.ref)
        activeIds += id
        val category = categoryById[entry.categoryId]
        val categoryDecision = categoryDecisionByRef[txn.ref]
        val payload = SYNC_JSON.encodeToString(
            SyncEntry(
                ownerMemberId = session.member,
                sourceKind = sourceKind,
                at = txn.at,
                amountRial = abs(signed),
                direction = if (signed > 0) "in" else "out",
                bank = txn.bank,
                categoryId = entry.categoryId,
                categoryName = entry.categoryFa,
                categoryKind = category?.kind ?: CategoryKind.EXPENSE,
                categoryGlyph = category?.glyph.orEmpty(),
                categoryEditorId = categoryDecision?.memberId.orEmpty().ifBlank { session.member },
                categoryUpdatedAt = categoryDecision?.updatedAt ?: 0L,
                merchant = txn.merchant,
            )
        )
        val contentHash = sha256Hex(payload)
        val previous = publications[id]
        if (previous != null && !previous.deleted && previous.contentHash == contentHash) continue
        val updatedAt = nextStamp(previous?.updatedAt, now)
        val publication = SyncPublication(id, sourceKind, contentHash, updatedAt, deleted = false)
        outgoing += PreparedRecord(
            wireRecord(session, id, "transaction", session.member, updatedAt, payload),
            publication,
        )
    }

    val tombstonePayload = SYNC_JSON.encodeToString(SyncTombstonePayload())
    for (publication in publications.values) {
        if (publication.sourceKind == "category" || publication.deleted || publication.id in activeIds) continue
        val updatedAt = nextStamp(publication.updatedAt, now)
        val deleted = publication.copy(contentHash = "", updatedAt = updatedAt, deleted = true)
        outgoing += PreparedRecord(
            wireRecord(
                session,
                publication.id,
                "transaction",
                session.member,
                updatedAt,
                tombstonePayload,
                deleted = true,
            ),
            deleted,
        )
    }

    val entriesByRef = ledger.entries.associateBy { it.txn.ref }
    for (decision in categoryDecisions) {
        if (decision.deleted) continue
        val categoryId = decision.value ?: continue
        val transaction = entriesByRef[decision.ref]?.txn
        if (
            transaction?.familyRef.isNullOrBlank() &&
            (transaction?.sourceKind == "sms" || decision.ref.startsWith("s:")) &&
            !shareSms
        ) continue
        val target = decision.familyRef.ifBlank {
            transaction?.familyRef?.takeIf(String::isNotBlank)
                ?: familyTxnId(session.member, decision.ref)
        }
        val category = categoryById[categoryId] ?: continue
        val editor = decision.memberId.ifBlank { session.member }
        val payload = SYNC_JSON.encodeToString(
            SyncCategoryPayload(
                target = target,
                categoryId = category.id,
                categoryName = category.nameFa,
                categoryKind = category.kind,
                categoryGlyph = category.glyph,
                editedByMemberId = editor,
            )
        )
        val id = categoryRecordId(target)
        val contentHash = sha256Hex(payload)
        val previous = publications[id]
        if (previous != null && !previous.deleted && previous.contentHash == contentHash) continue
        val updatedAt = nextStamp(previous?.updatedAt, decision.updatedAt)
        val publication = SyncPublication(id, "category", contentHash, updatedAt, deleted = false)
        outgoing += PreparedRecord(
            wireRecord(
                session,
                id,
                "category",
                ownerOfFamilyTxnId(target) ?: session.member,
                updatedAt,
                payload,
            ),
            publication,
        )
    }
    return outgoing
}

private fun safeSyncedText(value: String, max: Int, fallback: String = ""): String =
    value.filterNot(Char::isISOControl).trim().take(max).ifBlank { fallback }

/**
 * A mark another phone chose, kept only if this build draws it.
 *
 * Storing the name as sent would put a glyph from a newer edition in the database and leave the
 * lookup returning null for ever after. Blank is the honest answer instead — the category falls
 * back to the table by name, and an unknown name there already draws three dots.
 */
private fun safeSyncedGlyph(value: String): String =
    glyphNamed(value.filterNot(Char::isISOControl).trim())?.name.orEmpty()

fun categoryUpdateWins(
    existingAt: Long?,
    existingEditor: String,
    incomingAt: Long,
    incomingEditor: String,
): Boolean = existingAt == null ||
    incomingAt > existingAt ||
    (incomingAt == existingAt && incomingEditor > existingEditor) ||
    (incomingAt == 0L && existingAt == 0L && incomingEditor == existingEditor)

fun resolvedTransactionOwner(
    wireKind: String,
    wireOwner: String,
    authenticatedAuthor: String,
): String = if (wireKind == "legacy") authenticatedAuthor else wireOwner.ifBlank { authenticatedAuthor }

private suspend fun ensureMemberPlaceholder(durable: DurableDb, id: String, at: Long) {
    if (durable.familyMembers().get(id) == null) {
        durable.familyMembers().put(FamilyMember(id, "عضو خانواده", updatedAt = at))
    }
}

private suspend fun applyMember(
    durable: DurableDb,
    record: WireRecord,
    payload: SyncMemberPayload,
): Boolean {
    val memberId = payload.memberId.takeIf(String::isNotBlank) ?: return false
    if (record.ownerMemberId.isNotBlank() && record.ownerMemberId != memberId) return false
    val previous = durable.familyMembers().get(memberId)
    if (previous != null && previous.updatedAt > record.updatedAt) return false
    durable.familyMembers().put(
        FamilyMember(
            id = memberId,
            name = safeSyncedText(payload.name, 32, "عضو خانواده"),
            sharesSms = payload.sharesSms,
            updatedAt = record.updatedAt,
            deleted = record.deleted,
        )
    )
    return true
}

private suspend fun applyTransaction(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
    payload: SyncEntry,
): Boolean {
    val owner = resolvedTransactionOwner(record.kind, record.ownerMemberId, record.authorMemberId)
    if (owner.isBlank() || owner == session.member) return false
    if (payload.ownerMemberId.isNotBlank() && payload.ownerMemberId != owner) return false
    val familyRef = if (record.id.startsWith("txn:")) record.id else familyTxnId(owner, record.id)
    if (ownerOfFamilyTxnId(familyRef) != owner) return false
    val existing = durable.familyTxns().get(familyRef)
    if (record.deleted) {
        if (existing != null && existing.updatedAt <= record.updatedAt) {
            durable.familyTxns().put(existing.copy(updatedAt = record.updatedAt, deleted = true))
            return true
        }
        return false
    }
    if (payload.direction !in setOf("in", "out")) return false
    if (payload.amountRial !in 0..MAX_PLAUSIBLE_RIAL) return false
    if (existing != null && existing.updatedAt > record.updatedAt) return false
    ensureMemberPlaceholder(durable, owner, record.updatedAt)
    val sourceKind = payload.sourceKind.takeIf { it == "sms" || it == "manual" }
        ?: if (record.id.startsWith("s:")) "sms" else "manual"
    durable.familyTxns().put(
        FamilyTxn(
            id = familyRef,
            ownerMemberId = owner,
            sourceKind = sourceKind,
            at = payload.at,
            day = tehranDay(payload.at),
            amountRial = if (payload.direction == "in") payload.amountRial else -payload.amountRial,
            bank = safeSyncedText(payload.bank, 40, "MANUAL"),
            merchant = safeSyncedText(payload.merchant, 120),
            updatedAt = record.updatedAt,
        )
    )

    val categoryId = safeSyncedText(payload.categoryId, 80)
    if (categoryId.isNotBlank()) {
        if (durable.categories().get(categoryId) == null) {
            durable.categories().putAll(
                listOf(
                    Category(
                        id = categoryId,
                        nameFa = safeSyncedText(
                            payload.categoryName.ifBlank { payload.category },
                            60,
                            "دسته‌بندی نشده",
                        ),
                        kind = payload.categoryKind.takeIf {
                            it in setOf(CategoryKind.EXPENSE, CategoryKind.INCOME, CategoryKind.TRANSFER)
                        } ?: CategoryKind.EXPENSE,
                        sort = 500,
                        updatedAt = record.updatedAt,
                        glyph = safeSyncedGlyph(payload.categoryGlyph),
                    )
                )
            )
        }
        val localRef = familyLocalRef(familyRef)
        val existingDecision = durable.decisions().forRef(localRef)
            .firstOrNull { it.kind == DecisionKind.CATEGORY }
        val categoryUpdatedAt = payload.categoryUpdatedAt.coerceAtLeast(0L)
        val categoryEditorId = payload.categoryEditorId.ifBlank { owner }
        val incomingWins = categoryUpdateWins(
            existingDecision?.updatedAt,
            existingDecision?.memberId.orEmpty(),
            categoryUpdatedAt,
            categoryEditorId,
        )
        if (incomingWins) {
            durable.decisions().put(
                TxnDecision(
                    id = existingDecision?.id ?: categoryRecordId(familyRef),
                    ref = localRef,
                    kind = DecisionKind.CATEGORY,
                    value = categoryId,
                    createdAt = existingDecision?.createdAt ?: categoryUpdatedAt,
                    updatedAt = categoryUpdatedAt,
                    memberId = categoryEditorId,
                    familyRef = familyRef,
                )
            )
        }
    }
    return true
}

private suspend fun applyCategory(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
    payload: SyncCategoryPayload,
): Boolean {
    val targetOwner = ownerOfFamilyTxnId(payload.target) ?: return false
    val localRef = if (targetOwner == session.member) {
        localRefOfFamilyTxn(payload.target, session.member) ?: return false
    } else {
        familyLocalRef(payload.target)
    }
    val categoryId = safeSyncedText(payload.categoryId, 80)
    if (categoryId.isBlank()) return false
    val categoryKind = payload.categoryKind.takeIf {
        it in setOf(CategoryKind.EXPENSE, CategoryKind.INCOME, CategoryKind.TRANSFER)
    } ?: CategoryKind.EXPENSE
    if (durable.categories().get(categoryId) == null) {
        durable.categories().putAll(
            listOf(
                Category(
                    id = categoryId,
                    nameFa = safeSyncedText(payload.categoryName, 60, "دسته‌بندی نشده"),
                    kind = categoryKind,
                    sort = 500,
                    updatedAt = record.updatedAt,
                    glyph = safeSyncedGlyph(payload.categoryGlyph),
                )
            )
        )
    }
    val existing = durable.decisions().forRef(localRef).firstOrNull { it.kind == DecisionKind.CATEGORY }
    val editor = record.authorMemberId.ifBlank { payload.editedByMemberId }
    if (
        existing != null &&
        (existing.updatedAt > record.updatedAt ||
            (existing.updatedAt == record.updatedAt && existing.memberId >= editor))
    ) return false
    durable.decisions().put(
        TxnDecision(
            id = existing?.id ?: categoryRecordId(payload.target),
            ref = localRef,
            kind = DecisionKind.CATEGORY,
            value = categoryId,
            createdAt = existing?.createdAt ?: record.updatedAt,
            updatedAt = record.updatedAt,
            deleted = record.deleted,
            memberId = editor,
            familyRef = payload.target,
        )
    )
    return true
}

private suspend fun applyRecord(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
): Boolean {
    if (record.device == session.device) return false
    if (record.deleted && record.kind == "transaction") {
        val owner = record.ownerMemberId
        if (owner.isNotBlank() && owner != session.member) {
            val existing = durable.familyTxns().get(record.id)
            if (existing != null && existing.updatedAt <= record.updatedAt) {
                durable.familyTxns().put(existing.copy(updatedAt = record.updatedAt, deleted = true))
                return true
            }
        }
        return false
    }
    val plain = openSealed(session.key, record.nonce, record.body) ?: return false
    return when (record.kind) {
        "member" -> runCatching { SYNC_JSON.decodeFromString<SyncMemberPayload>(plain) }
            .getOrNull()?.let { applyMember(durable, record, it) } ?: false
        "category" -> runCatching { SYNC_JSON.decodeFromString<SyncCategoryPayload>(plain) }
            .getOrNull()?.let { applyCategory(durable, session, record, it) } ?: false
        "transaction", "legacy" -> runCatching { SYNC_JSON.decodeFromString<SyncEntry>(plain) }
            .getOrNull()?.let { applyTransaction(durable, session, record, it) } ?: false
        else -> false
    }
}

suspend fun syncNow(
    durable: DurableDb,
    derived: DerivedDb,
    session: SyncSession,
    now: Long = System.currentTimeMillis(),
): SyncResult = withContext(Dispatchers.IO) {
    registerIdentity(session, durable)
    val outgoing = outgoingRecords(durable, derived, session, now)
    var sent = 0
    for (chunk in outgoing.chunked(200)) {
        request(
            "${session.base}/v1/sync",
            "POST",
            session.token,
            SYNC_JSON.encodeToString(PushBody(chunk.map { it.wire })),
        )
        val publications = chunk.mapNotNull { it.publication }
        if (publications.isNotEmpty()) durable.syncPublications().putAll(publications)
        sent += chunk.size
    }

    var cursor = durable.meta().get(META_SYNC_SEQ)?.toLongOrNull() ?: 0L
    var received = 0
    do {
        val pulled = SYNC_JSON.decodeFromString<PullBody>(
            request("${session.base}/v1/sync?since=$cursor&limit=1000", "GET", session.token, null)
        )
        val previous = cursor
        val nextCursor = maxOf(cursor, pulled.seq)
        received += durable.withTransaction {
            var applied = 0
            for (record in pulled.records) if (applyRecord(durable, session, record)) applied++
            durable.meta().put(DurableMeta(META_SYNC_SEQ, nextCursor.toString()))
            applied
        }
        cursor = nextCursor
        val more = pulled.records.size >= 1000 && cursor > previous
    } while (more)
    SyncResult(sent, received)
}
