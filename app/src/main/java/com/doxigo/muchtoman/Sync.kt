package com.doxigo.muchtoman

import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
const val META_SYNC_TOKEN_AT = "sync_token_at"
const val META_SYNC_ROTATION = "sync_rotation"
const val META_SYNC_DEVICE = "sync_device"
const val META_SYNC_MEMBER = "sync_member"
const val META_SYNC_SCOPE = "sync_scope"
const val META_SYNC_KEY = "sync_key"
const val META_SYNC_SEQ = "sync_seq"
const val META_SYNC_SHARE_SMS = "sync_share_sms"
const val META_SYNC_SHARE_ASSETS = "sync_share_assets"
/** Comma-joined bank names kept out of sharing — transactions and balances both. */
const val META_SYNC_EXCLUDED_BANKS = "sync_excluded_banks"
/** The household founder, as the server names them on every pull. */
const val META_SYNC_PRIMARY = "sync_primary_member"
const val META_SYNC_IDENTITY_OK = "sync_identity_ok"

fun parseExcludedBanks(raw: String?): Set<String> =
    raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

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

private val familySyncMutex = Mutex()

internal suspend fun <T> withFamilySync(block: suspend () -> T): T =
    withContext(Dispatchers.IO) { familySyncMutex.withLock { block() } }

internal fun sameHouseholdSession(expected: SyncSession, actual: SyncSession): Boolean =
    expected.base == actual.base && expected.token.substringBefore('.') == actual.token.substringBefore('.') &&
        expected.member == actual.member && expected.device == actual.device && expected.scope == actual.scope &&
        expected.key.contentEquals(actual.key)

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
)

@Serializable
private data class SyncMemberPayload(
    val kind: String = "member",
    val memberId: String,
    val name: String,
    val sharesSms: Boolean,
    /** The face they picked — see [FamilyMember.avatar]. Defaulted, so old builds' records parse. */
    val avatar: String = "",
)

/**
 * One line of a member's shared دارایی: their own name for it and its value in Toman, priced on
 * their phone with their rates. Nothing here is re-derived on arrival — the owner's figure is
 * the figure, exactly as the owner's parse of a message is the transaction.
 */
@Serializable
data class AssetShareItem(val name: String, val toman: Double)

@Serializable
private data class SyncAssetPayload(
    val kind: String = "asset",
    val memberId: String,
    val totalToman: Double,
    val items: List<AssetShareItem> = emptyList(),
)

/** A member's shared دارایی as the screens read it: who, what, and their own total. */
data class FamilyAssetView(
    val memberId: String,
    val name: String,
    val items: List<AssetShareItem>,
    val totalToman: Double,
)

fun decodeAssetItems(json: String): List<AssetShareItem> =
    runCatching { SYNC_JSON.decodeFromString<List<AssetShareItem>>(json) }.getOrDefault(emptyList())

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

 * Somebody's words about one transaction, on its own record rather than inside the
 * transaction's.
 *
 * A note is written by whoever is looking at the row — «کادوی تولد مامان» on her husband's
 * grocery run is hers to add, exactly as the category on it is hers to change — so it cannot
 * ride inside the owner's transaction record, which only the owner may write. This is the same
 * shape [SyncCategoryPayload] takes and for the same reason, down to the target naming the
 * family reference rather than either phone's local one.
 *
 * A blank [note] is a note taken back. There is no tombstone: the record id is a hash of its
 * target, so a contentless delete would name a row the receiver cannot resolve — an empty note
 * says the same thing and says it somewhere the receiver can act on.
 */
@Serializable
private data class SyncNotePayload(
    val kind: String = "note",
    val target: String,
    val note: String,
    val editedByMemberId: String,
)

/**
 * A delete, said inside the ciphertext.
 *
 * `deleted`, `updatedAt` and `ownerMemberId` ride outside the seal, so an honoured contentless
 * tombstone would let a compromised server erase anything by flipping a bit on a stored row. A
 * delete is believed only when this payload authenticates under the scope key *and* names the
 * record it arrived on. No field has a default: the old contentless shape must fail to parse,
 * and it may — the protocol has never shipped in a tagged release, so there is nothing to keep
 * reading.
 */
@Serializable
internal data class SyncTombstonePayload(val v: Int, val id: String, val deleted: Boolean)

internal fun parseTombstone(plain: String): SyncTombstonePayload? =
    runCatching { SYNC_JSON.decodeFromString<SyncTombstonePayload>(plain) }
        .getOrNull()?.takeIf { it.deleted && it.id.isNotBlank() }

/**
 * The client half of the server's 24-hour stamp clamp, wider so an honest offline device that
 * pushed through a skewed peer still converges: a record claiming to be written more than two
 * days in the future is treated as written at the horizon, so one wrong clock — or one device
 * that dodges the server clamp — cannot pin a record against every later honest edit.
 */
internal const val MAX_SYNC_STAMP_SKEW_MS = 48L * 60 * 60 * 1000

internal fun clampSyncStamp(stamp: Long, now: Long): Long = minOf(stamp, now + MAX_SYNC_STAMP_SKEW_MS)

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
private data class PullBody(
    val seq: Long = 0,
    val records: List<WireRecord> = emptyList(),
    val primaryMemberId: String = "",
    val hasMore: Boolean? = null,
    val rotationClientSecret: Boolean = false,
)

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

@Serializable
private data class RemoveMemberBody(val member: String, val record: WireRecord)

@Serializable
private data class LeaveBody(val record: WireRecord)

@Serializable
private data class ClampedStamp(val id: String = "", val updatedAt: Long = 0)

@Serializable
private data class PushAck(val seq: Long = 0, val clamped: List<ClampedStamp> = emptyList())

internal class SyncHttpException(val status: Int, detail: String = "") :
    IllegalStateException("sync $status: $detail")

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
            throw SyncHttpException(conn.responseCode, detail.take(120))
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
private fun assetRecordId(memberId: String): String = "asset:$memberId"
private fun categoryRecordId(familyRef: String): String = "category:${sha256Hex(familyRef)}"

/**
 * A shared budget or goal's record, keyed by the goal and **not by who made it**.
 *
 * `goal:<memberId>:<goalId>` is the shape a transaction uses, and it is the wrong one here: it
 * would name an owner in the id, and the server would then have to refuse a write from anybody
 * else. A household budget one of them cannot adjust is a budget they cannot keep, so the id says
 * only which figure is meant and ownership is a fact on the record rather than a lock on it.
 */
internal fun goalRecordId(goalId: String): String = "goal:$goalId"

/**
 * One goal as it goes on the wire, built here and nowhere else.
 *
 * The single constructor is the fixed point: a phone that receives a record, stores it and then
 * asks this function what it would send gets the same bytes back, so the content hash matches and
 * nothing is republished. Every field is put through the same cap the receiving side applies, so
 * a name one byte too long cannot make the two sides disagree for ever.
 */
internal fun goalPayload(goal: Goal): String = SYNC_JSON.encodeToString(
    SyncGoalPayload(
        goalId = goal.id,
        nameFa = safeSyncedText(goal.nameFa, 40, "بی‌نام"),
        targetRial = goal.targetRial,
        goalKind = goal.kind,
        categoryId = safeSyncedText(goal.categoryId.orEmpty(), 80),
        period = goal.period,
        startsOn = goal.startsOn,
        endsOn = goal.endsOn,
        createdAt = goal.createdAt,
        ownerMemberId = goal.ownerMemberId,
        editedByMemberId = goal.editedByMemberId,
    )
)
private fun noteRecordId(familyRef: String): String = "note:${sha256Hex(familyRef)}"

/** One fresh household on the server: a random id, claimed for this member and device. */
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
    withFamilySync {
        val session = claimFreshHousehold(base, newIdentity(), newIdentity())
        saveSession(durable, session)
        resetFamilySharing(durable)
        durable.familyMembers().put(
            FamilyMember(session.member, cleanMemberName(memberName), sharesSms = false, updatedAt = System.currentTimeMillis())
        )
        session
    }

suspend fun saveSession(durable: DurableDb, session: SyncSession) = durable.withTransaction {
    durable.meta().delete(META_SYNC_ROTATION)
    durable.meta().put(DurableMeta(META_SYNC_BASE, session.base))
    durable.meta().put(DurableMeta(META_SYNC_TOKEN, session.token))
    // Every caller of this is a moment a token was minted, so the issue date rides along; it is
    // what lets a later sync notice the token has been in one place for a month and rotate it.
    durable.meta().put(DurableMeta(META_SYNC_TOKEN_AT, System.currentTimeMillis().toString()))
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

suspend fun invite(session: SyncSession, durable: DurableDb): String = withFamilySync {
    val active = activeSession(durable, session)
    registerIdentity(active, durable)
    val response = request(
        "${active.base}/v1/invite",
        "POST",
        active.token,
        SYNC_JSON.encodeToString(InviteBody(listOf(active.scope))),
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
    resetFamilySharing(durable)
    durable.familyMembers().put(
        FamilyMember(session.member, cleanMemberName(memberName), sharesSms = false, updatedAt = System.currentTimeMillis())
    )
}

private suspend fun resetFamilySharing(durable: DurableDb) {
    durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, "false"))
    durable.meta().put(DurableMeta(META_SYNC_SHARE_ASSETS, "false"))
    durable.meta().put(DurableMeta(META_SYNC_EXCLUDED_BANKS, ""))
}

suspend fun joinHousehold(link: String, durable: DurableDb, memberName: String): SyncSession =
    withFamilySync {
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
    withFamilySync {
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
 * The server publishes the sealed tombstone and revokes every device in one transaction. Only
 * after that succeeds does this phone bury its local member row.
 *
 * What this does not do, on purpose: it does not touch their past transactions here or anywhere,
 * because they were already seen — the same honesty as the privacy sentence on the screen. And it
 * does not re-key: an ex-member who somehow keeps reading ciphertext still holds the scope key.
 * [renewHousehold] is the answer to that.
 */
suspend fun removeFamilyMember(session: SyncSession, durable: DurableDb, memberId: String): Unit =
    withFamilySync {
        val active = activeSession(durable, session)
        require(memberId != active.member) { "not for leaving" }
        val member = durable.familyMembers().get(memberId)
        val stamp = nextStamp(member?.updatedAt, System.currentTimeMillis())
        val recordId = memberRecordId(memberId)
        val tombstone = wireRecord(
            active,
            recordId,
            "member",
            memberId,
            stamp,
            SYNC_JSON.encodeToString(SyncTombstonePayload(v = 1, id = recordId, deleted = true)),
            deleted = true,
        )
        request(
            "${active.base}/v1/remove",
            "POST",
            active.token,
            SYNC_JSON.encodeToString(RemoveMemberBody(memberId, tombstone)),
        )
        durable.familyMembers().put(
            (member ?: FamilyMember(memberId, "عضو خانواده", updatedAt = stamp))
                .copy(updatedAt = stamp, deleted = true)
        )
    }

/**
 * This phone walks out on its own feet — the other side of [removeFamilyMember].
 *
 * The goodbye and token revocation are one server operation. Only after it succeeds is the local
 * household buried and the session erased in one Room transaction.
 *
 * The same honesty as removal: nothing already seen is taken back, and the key this phone holds
 * is not un-held. «نو کردن خانواده» on a remaining phone is the answer to that.
 */
suspend fun leaveFamily(session: SyncSession, durable: DurableDb): Unit = withFamilySync {
    val active = activeSession(durable, session)
    val recordId = memberRecordId(active.member)
    val stamp = nextStamp(durable.familyMembers().get(active.member)?.updatedAt, System.currentTimeMillis())
    val tombstone = wireRecord(
        active,
        recordId,
        "member",
        active.member,
        stamp,
        SYNC_JSON.encodeToString(SyncTombstonePayload(v = 1, id = recordId, deleted = true)),
        deleted = true,
    )
    request(
        "${active.base}/v1/leave",
        "POST",
        active.token,
        SYNC_JSON.encodeToString(LeaveBody(tombstone)),
    )
    durable.withTransaction {
        buryHousehold(durable, keepMember = null)
        // loadSession treats any stored base as a session to resume, so the keys must go, not blank.
        durable.meta().delete(META_SYNC_BASE)
        durable.meta().delete(META_SYNC_TOKEN)
        durable.meta().delete(META_SYNC_SCOPE)
        durable.meta().delete(META_SYNC_KEY)
        durable.meta().delete(META_SYNC_PRIMARY)
    }
}

/**
 * Cryptographic eviction: a fresh household under a fresh key, because [removeFamilyMember]
 * alone leaves the ex-member holding the scope key — able to read any future ciphertext they
 * ever get their hands on.
 *
 * This device keeps its identity and walks alone into a new household: new id, new token, new
 * key, same person. The publication marks are buried so the next ordinary sync re-pushes every
 * record this device owns, and the old household's copy of everyone else is buried locally —
 * that ledger stops updating, and the shared one starts over. Everyone remaining has to scan a
 * fresh QR; the copy on the screen says so in as many words.
 */
suspend fun renewHousehold(durable: DurableDb): SyncSession = withFamilySync {
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
    durable.meta().delete(META_SYNC_ROTATION)
    durable.meta().put(DurableMeta(META_SYNC_SEQ, "0"))
    durable.meta().put(DurableMeta(META_SYNC_IDENTITY_OK, "false"))
    resetFamilySharing(durable)
    val publications = durable.syncPublications().all()
    if (publications.isNotEmpty()) {
        durable.syncPublications().putAll(publications.map { it.copy(deleted = true) })
    }
    val now = System.currentTimeMillis()
    for (member in durable.familyMembers().all()) {
        if (member.id == keepMember) {
            if (member.sharesSms) {
                durable.familyMembers().put(
                    member.copy(sharesSms = false, updatedAt = nextStamp(member.updatedAt, now))
                )
            }
            continue
        }
        durable.familyMembers().put(member.copy(updatedAt = nextStamp(member.updatedAt, now), deleted = true))
    }
    // Their rows would double the moment they rejoin and re-push under a fresh member id,
    // so the old copies go now, while the copy on screen is saying "از نو".
    for (txn in durable.familyTxns().all()) {
        durable.familyTxns().put(txn.copy(updatedAt = nextStamp(txn.updatedAt, now), deleted = true))
    }
    // Every shared دارایی row here belongs to somebody in the household being left.
    for (asset in durable.familyAssets().all()) {
        durable.familyAssets().put(asset.copy(updatedAt = nextStamp(asset.updatedAt, now), deleted = true))
    }
    // Budgets and goals split by who made them, which is the only place this cleanup is not a
    // sweep. Somebody else's shared cap goes the way their transactions go — it was the
    // household's figure and there is no household. Hers stay, because they are hers, and land
    // back on «مال خودم»: the phone has nobody to share with until it pairs again, and a row
    // still marked shared would start publishing to the next household the day she joined it.
    for (goal in durable.goals().all()) {
        if (goal.deleted) continue
        val hers = goal.ownerMemberId.isBlank() || goal.ownerMemberId == keepMember
        durable.goals().put(
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
    /** This member's دارایی as they share it, or null when sharing is off. */
    assets: List<AssetShareItem>?,
): List<PreparedRecord> {
    val shareSms = durable.meta().get(META_SYNC_SHARE_SMS).toBoolean()
    val excludedBanks = parseExcludedBanks(durable.meta().get(META_SYNC_EXCLUDED_BANKS))
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
        SyncMemberPayload(
            memberId = member.id,
            name = member.name,
            sharesSms = member.sharesSms,
            avatar = member.avatar,
        )
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
        // A bank she set aside: its rows never leave, and ones already out are swept below —
        // dropping out of activeIds is what turns yesterday's share into today's tombstone.
        if (txn.bank in excludedBanks) continue
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

    for (publication in publications.values) {
        // Only transaction publications sweep here: category records answer to their decisions,
        // and the asset record has its own unshare below — a "transaction" tombstone under an
        // asset: id would be refused by the server as a kind mismatch anyway.
        if (publication.sourceKind == "category" || publication.sourceKind == "asset") continue
        // Only transaction publications sweep here: category and note records answer to their
        // own decisions, and the asset and goal records have their own unshare below — a
        // "transaction" tombstone under an asset: id would be refused by the server as a kind
        // mismatch anyway.
        if (publication.sourceKind in setOf("category", "note", "asset", "goal")) continue
        if (publication.deleted || publication.id in activeIds) continue
        val updatedAt = nextStamp(publication.updatedAt, now)
        val deleted = publication.copy(contentHash = "", updatedAt = updatedAt, deleted = true)
        outgoing += PreparedRecord(
            wireRecord(
                session,
                publication.id,
                "transaction",
                session.member,
                updatedAt,
                // The record's own id, sealed in: a delete another phone will only believe when
                // the ciphertext authenticates and names the record it arrived on.
                SYNC_JSON.encodeToString(SyncTombstonePayload(v = 1, id = publication.id, deleted = true)),
                deleted = true,
            ),
            deleted,
        )
    }

    // دارایی is one record per person, replaced wholesale: prices move every day, so it is
    // republished whenever its content hash moves and tombstoned the sync after sharing stops.
    val assetId = assetRecordId(session.member)
    val assetPublication = publications[assetId]
    if (assets != null) {
        // The same 64-item ceiling the receivers hold; past it the payload is a parse gone
        // wrong, and a record that outgrows the server's body cap would wedge the whole push.
        val shared = safeAssetShareItems(assets)
        val payload = SYNC_JSON.encodeToString(
            SyncAssetPayload(
                memberId = session.member,
                totalToman = shared.sumOf { it.toman },
                items = shared,
            )
        )
        val contentHash = sha256Hex(payload)
        if (assetPublication == null || assetPublication.deleted || assetPublication.contentHash != contentHash) {
            val updatedAt = nextStamp(assetPublication?.updatedAt, now)
            outgoing += PreparedRecord(
                wireRecord(session, assetId, "asset", session.member, updatedAt, payload),
                SyncPublication(assetId, "asset", contentHash, updatedAt, deleted = false),
            )
        }
    } else if (
        assetPublication != null && !assetPublication.deleted &&
        !durable.meta().get(META_SYNC_SHARE_ASSETS).toBoolean()
    ) {
        // Only her switch unshares. A null with the switch still on is a caller with no prices
        // to hand — the background worker — and must leave the shared record standing, not
        // tombstone it and let the next app open flap it back.
        val updatedAt = nextStamp(assetPublication.updatedAt, now)
        outgoing += PreparedRecord(
            wireRecord(
                session,
                assetId,
                "asset",
                session.member,
                updatedAt,
                SYNC_JSON.encodeToString(SyncTombstonePayload(v = 1, id = assetId, deleted = true)),
                deleted = true,
            ),
            assetPublication.copy(contentHash = "", updatedAt = updatedAt, deleted = true),
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
        if (!decisionMayLeave(decision, transaction, shareSms, session.member, excludedBanks)) continue
        val target = familyTargetOf(decision, transaction, session.member) ?: continue
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

    // Notes go out the way categories do, on records of their own, because whoever is reading a
    // row is who writes one — see [SyncNotePayload]. Retracted decisions are walked too: a note
    // she has taken back must be published as gone, or her words stay on every other phone.
    for (decision in durable.decisions().ofKindWithRetracted(DecisionKind.NOTE)) {
        val transaction = entriesByRef[decision.ref]?.txn
        if (!decisionMayLeave(decision, transaction, shareSms, session.member, excludedBanks)) continue
        val target = familyTargetOf(decision, transaction, session.member) ?: continue
        val id = noteRecordId(target)
        val previous = publications[id]
        val note = if (decision.deleted) "" else decision.value.orEmpty()
        // A note nobody was ever told about and that is blank now has nothing to say. Only one
        // the household has already read is worth the record that takes it back.
        if (note.isBlank() && (previous == null || previous.deleted)) continue
        val payload = SYNC_JSON.encodeToString(
            SyncNotePayload(
                target = target,
                note = note,
                editedByMemberId = decision.memberId.ifBlank { session.member },
            )
        )
        val contentHash = sha256Hex(payload)
        if (previous != null && !previous.deleted && previous.contentHash == contentHash) continue
        val updatedAt = nextStamp(previous?.updatedAt, decision.updatedAt)
        outgoing += PreparedRecord(
            wireRecord(
                session,
                id,
                "note",
                ownerOfFamilyTxnId(target) ?: session.member,
                updatedAt,
                payload,
            ),
            SyncPublication(id, "note", contentHash, updatedAt, deleted = false),
        )
    }
    return outgoing
}

/**
 * Which shared transaction an answer is about, in the household's own naming.
 *
 * The decision carries it once its row has been in a family; failing that the transaction does;
 * failing both it is one of her own rows and the reference is hers to build. A row that came
 * *from* the family carrying neither cannot be named to anybody — `f:` is a local hash of a
 * reference the others would have to already know — so it is left alone rather than published
 * under a guess at somebody's transaction id.
 */
private fun familyTargetOf(decision: TxnDecision, txn: Txn?, member: String): String? =
    decision.familyRef.takeIf(String::isNotBlank)
        ?: txn?.familyRef?.takeIf(String::isNotBlank)
        ?: familyTxnId(member, decision.ref).takeUnless { decision.ref.startsWith("f:") }

/**
 * is a leak dressed as bookkeeping. A row that came *from* the family is already shared by its
 * owner, and her answer about one is hers to send.
 *
 * Only her *own* answers, though. Somebody else's category or note arrived as their record and
 * stays theirs: the server keeps it, so nobody needs it echoed, and echoing it would rewrite the
 * record under this device's authorship — which is the line that says who wrote the words. A
 * blank author is a decision from before the household existed, and that one is hers.
 */
internal fun decisionMayLeave(
    decision: TxnDecision,
    txn: Txn?,
    shareSms: Boolean,
    member: String,
    excludedBanks: Set<String>,
): Boolean {
    if (decision.memberId.isNotBlank() && decision.memberId != member) return false
    if (txn != null && txn.familyRef.isNotBlank()) return true
    if (!shareSms && (txn?.sourceKind == "sms" || decision.ref.startsWith("s:"))) return false
    return txn == null || txn.bank !in excludedBanks
}

private fun safeSyncedText(value: String, max: Int, fallback: String = ""): String =
    value.filterNot(Char::isISOControl).trim().take(max).ifBlank { fallback }

/**
 * Somebody else's words, held to the shape this app writes them in.
 *
 * The newline survives, unlike in every other synced string: a note is prose and the field she
 * types it into is two lines tall, so flattening it would make her paragraph read as one line on
 * her husband's phone and nowhere else. Everything else a control character can be is dropped,
 * because nothing else in a note is text.
 */
internal fun safeSyncedNote(value: String): String =
    value.filterNot { it.isISOControl() && it != '\n' }.trim().take(MAX_NOTE_CHARS)

/**
 * A face another phone chose, held to the two shapes this build renders: a short emoji, or a
 * photo small enough that it was made by [avatarThumbnail]'s own cap. Anything else — a novel
 * prefix, a photo blown past the cap — lands blank, and blank is the initial, which is never
 * wrong. The bytes themselves are not decoded here; [MemberFace] decodes lazily and falls back
 * to the initial itself if they turn out not to be an image.
 */
internal fun safeSyncedAvatar(value: String): String = when {
    value.startsWith(AVATAR_PHOTO_PREFIX) -> value.takeIf { it.length <= AVATAR_B64_MAX }.orEmpty()
    else -> value.filterNot(Char::isISOControl).trim().take(16)
}

/**
 * A mark another phone chose, kept only if this build draws it.
 *
 * Storing the name as sent would put a glyph from a newer edition in the database and leave the
 * lookup returning null for ever after. Blank is the honest answer instead — the category falls
 * back to the table by name, and an unknown name there already draws three dots.
 */
private fun safeSyncedGlyph(value: String): String =
    glyphNamed(value.filterNot(Char::isISOControl).trim())?.name.orEmpty()

/**
 * Whether an answer arriving from another phone replaces the one this phone already holds.
 *
 * Last write wins, with the editor's id breaking a same-millisecond tie so two people who
 * disagree in the same instant converge on one answer instead of overwriting each other for
 * ever. The same rule the server applies to the record, one level down — to the decision inside
 * it — and shared by the category and the note because «who wrote this last» is one question.
 */
internal fun syncedEditWins(
    existingAt: Long?,
    existingEditor: String,
    incomingAt: Long,
    incomingEditor: String,
): Boolean = existingAt == null ||
    existingAt < incomingAt ||
    (existingAt == incomingAt && existingEditor < incomingEditor)

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
            avatar = safeSyncedAvatar(payload.avatar),
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
    now: Long,
): Boolean {
    val owner = resolvedTransactionOwner(record.kind, record.ownerMemberId, record.authorMemberId)
    if (owner.isBlank() || owner == session.member) return false
    if (payload.ownerMemberId.isNotBlank() && payload.ownerMemberId != owner) return false
    val familyRef = if (record.id.startsWith("txn:")) record.id else familyTxnId(owner, record.id)
    if (ownerOfFamilyTxnId(familyRef) != owner) return false
    val existing = durable.familyTxns().get(familyRef)
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
            transfer = payload.transfer,
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
        // Same skew bound as the envelope stamp: this one rides inside the payload, so it needs
        // its own clamp or a skewed editor pins the category for ever.
        val categoryUpdatedAt = clampSyncStamp(payload.categoryUpdatedAt.coerceAtLeast(0L), now)
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
    if (!syncedEditWins(existing?.updatedAt, existing?.memberId.orEmpty(), record.updatedAt, editor)) return false
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

/**
 * Somebody's words about a row, arriving from their phone.
 *
 * Held to the rules the category is held to: the target has to name a transaction inside this
 * household, the local reference follows from whose row it is, and the later write wins with the
 * editor breaking a same-millisecond tie so two phones converge instead of flapping. The guard
 * reads the retracted row too — a note taken back here must not be resurrected by an older copy
 * of itself coming back round the household.
 *
 * A blank note is a note taken back, and lands as a retracted decision rather than an empty
 * one, which is the same row [setNote] would have written had she deleted it on this phone.
 */
private suspend fun applyNote(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
    payload: SyncNotePayload,
): Boolean {
    val targetOwner = ownerOfFamilyTxnId(payload.target) ?: return false
    val localRef = if (targetOwner == session.member) {
        localRefOfFamilyTxn(payload.target, session.member) ?: return false
    } else {
        familyLocalRef(payload.target)
    }
    val note = safeSyncedNote(payload.note)
    val existing = durable.decisions().answerFor(localRef, DecisionKind.NOTE)
    val editor = record.authorMemberId.ifBlank { payload.editedByMemberId }
    if (!syncedEditWins(existing?.updatedAt, existing?.memberId.orEmpty(), record.updatedAt, editor)) return false
    durable.decisions().put(
        TxnDecision(
            id = existing?.id ?: noteRecordId(payload.target),
            ref = localRef,
            kind = DecisionKind.NOTE,
            value = note,
            createdAt = existing?.createdAt ?: record.updatedAt,
            updatedAt = record.updatedAt,
            deleted = note.isEmpty(),
            memberId = editor,
            familyRef = payload.target,
        )
    )
    return true
}
/**
 * Somebody's shared دارایی, kept as sent: their names, their prices. Validated the way every
 * other record is — the id, the envelope owner and the sealed payload must all name the same
 * person, and that person must not be this phone's own member.
 */
private suspend fun applyAsset(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
    payload: SyncAssetPayload,
): Boolean {
    val memberId = record.id.removePrefix("asset:")
    if (!isValidSyncIdentity(memberId) || memberId == session.member) return false
    if (record.ownerMemberId != memberId || payload.memberId != memberId) return false
    val existing = durable.familyAssets().get(memberId)
    if (existing != null && existing.updatedAt > record.updatedAt) return false
    val items = payload.items.take(64)
        .map { AssetShareItem(safeSyncedText(it.name, 60, "دارایی"), it.toman) }
        .filter { it.toman.isFinite() && it.toman in 0.0..MAX_PLAUSIBLE_RIAL.toDouble() }
    ensureMemberPlaceholder(durable, memberId, record.updatedAt)
    durable.familyAssets().put(
        FamilyAsset(
            memberId = memberId,
            itemsJson = SYNC_JSON.encodeToString(items),
            // Their figure where it is sane, the sum of what survived where it is not — a total
            // out past ten trillion Toman is a corruption, not a fortune.
            totalToman = payload.totalToman
                .takeIf { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_RIAL.toDouble() }
                ?: items.sumOf { it.toman },
            updatedAt = record.updatedAt,
        )
    )
    return true
}

private suspend fun applyAssetTombstone(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
): Boolean {
    val memberId = record.id.removePrefix("asset:")
    if (!isValidSyncIdentity(memberId) || memberId == session.member) return false
    val existing = durable.familyAssets().get(memberId) ?: return false
    if (existing.updatedAt > record.updatedAt) return false
    durable.familyAssets().put(existing.copy(updatedAt = record.updatedAt, deleted = true))
    return true
}

/** A verified tombstone for someone else's transaction: mark the local copy gone. */
private suspend fun applyTransactionTombstone(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
): Boolean {
    val owner = record.ownerMemberId
    if (owner.isBlank() || owner == session.member) return false
    val existing = durable.familyTxns().get(record.id) ?: return false
    if (existing.updatedAt > record.updatedAt) return false
    durable.familyTxns().put(existing.copy(updatedAt = record.updatedAt, deleted = true))
    return true
}

/**
 * A verified tombstone for a member record: this is how the rest of the household learns someone
 * was removed, since the removed person's own device can no longer say anything. Never applied to
 * this device's own member — if that ever arrives, the next request will be a 401 and honest
 * about it, and a phone should not erase its owner from her own screen on a server's word.
 */
private suspend fun applyMemberTombstone(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
): Boolean {
    val memberId = record.id.removePrefix("member:")
    if (!isValidSyncIdentity(memberId) || memberId == session.member) return false
    val existing = durable.familyMembers().get(memberId)
    if (existing != null && existing.updatedAt > record.updatedAt) return false
    durable.familyMembers().put(
        (existing ?: FamilyMember(memberId, "عضو خانواده", updatedAt = record.updatedAt))
            .copy(updatedAt = record.updatedAt, deleted = true)
    )
    return true
}

private suspend fun applyRecord(
    durable: DurableDb,
    session: SyncSession,
    record: WireRecord,
    now: Long,
): Boolean {
    if (record.device == session.device) return false
    val plain = openSealed(session.key, record.nonce, record.body) ?: return false
    if (record.deleted) {
        // A delete is only a delete when the sealed body says so and names this very record.
        // The flag, the stamp and the owner all ride in plaintext, so honouring them alone would
        // let a compromised server forge a tombstone out of any stored row — or move a real one
        // onto a different record. Old contentless tombstones fail [parseTombstone] and are
        // rejected outright; the protocol never shipped in a tagged release, so nothing is owed
        // to that shape.
        val tombstone = parseTombstone(plain) ?: return false
        if (tombstone.id != record.id) return false
        return when (record.kind) {
            "transaction" -> applyTransactionTombstone(durable, session, record)
            "member" -> applyMemberTombstone(durable, session, record)
            "asset" -> applyAssetTombstone(durable, session, record)
            else -> false
        }
    }
    return when (record.kind) {
        "member" -> runCatching { SYNC_JSON.decodeFromString<SyncMemberPayload>(plain) }
            .getOrNull()?.let { applyMember(durable, record, it) } ?: false
        "category" -> runCatching { SYNC_JSON.decodeFromString<SyncCategoryPayload>(plain) }
            .getOrNull()?.let { applyCategory(durable, session, record, it) } ?: false
        "note" -> runCatching { SYNC_JSON.decodeFromString<SyncNotePayload>(plain) }
            .getOrNull()?.let { applyNote(durable, session, record, it) } ?: false
        "asset" -> runCatching { SYNC_JSON.decodeFromString<SyncAssetPayload>(plain) }
            .getOrNull()?.let { applyAsset(durable, session, record, it) } ?: false
        "goal" -> runCatching { SYNC_JSON.decodeFromString<SyncGoalPayload>(plain) }
            .getOrNull()?.let { applyGoal(durable, session, record, it) } ?: false
        "transaction", "legacy" -> runCatching { SYNC_JSON.decodeFromString<SyncEntry>(plain) }
            .getOrNull()?.let { applyTransaction(durable, session, record, it, now) } ?: false
        else -> false
    }
}

private const val TOKEN_ROTATE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

@Serializable
internal data class PendingSyncRotation(val oldToken: String, val newToken: String, val startedAt: Long)

internal suspend fun finishSyncRotation(
    pending: PendingSyncRotation,
    send: suspend (token: String, secret: String) -> Unit,
) {
    val secret = pending.newToken.substringAfter('.')
    try {
        send(pending.oldToken, secret)
    } catch (error: SyncHttpException) {
        if (error.status != 401) throw error
        send(pending.newToken, secret)
    }
}

private suspend fun recoverTokenRotation(durable: DurableDb, session: SyncSession): SyncSession {
    val raw = durable.meta().get(META_SYNC_ROTATION) ?: return session
    val pending = SYNC_JSON.decodeFromString<PendingSyncRotation>(raw)
    if (session.token != pending.oldToken && session.token != pending.newToken) {
        durable.meta().delete(META_SYNC_ROTATION)
        return session
    }
    finishSyncRotation(pending) { token, secret ->
        val response = request(
            "${session.base}/v1/rotate", "POST", token,
            SYNC_JSON.encodeToString(SecretBody(secret = secret)),
        )
        check(SYNC_JSON.decodeFromString<SecretBody>(response).secret == secret) { "rotation mismatch" }
    }
    durable.withTransaction {
        durable.meta().put(DurableMeta(META_SYNC_TOKEN, pending.newToken))
        durable.meta().put(DurableMeta(META_SYNC_TOKEN_AT, pending.startedAt.toString()))
        durable.meta().delete(META_SYNC_ROTATION)
    }
    return session.copy(token = pending.newToken)
}

private suspend fun activeSession(durable: DurableDb, expected: SyncSession): SyncSession {
    val actual = loadSession(durable) ?: error("no household")
    check(sameHouseholdSession(expected, actual)) { "household changed" }
    return recoverTokenRotation(durable, actual)
}

private suspend fun rotateTokenIfStale(durable: DurableDb, session: SyncSession, now: Long) {
    val issuedAt = durable.meta().get(META_SYNC_TOKEN_AT)?.toLongOrNull()
    if (issuedAt == null) {
        durable.meta().put(DurableMeta(META_SYNC_TOKEN_AT, now.toString()))
        return
    }
    if (now - issuedAt < TOKEN_ROTATE_AFTER_MS) return
    val secret = hexOf(ByteArray(32).also { SYNC_RANDOM.nextBytes(it) })
    val pending = PendingSyncRotation(session.token, "${session.token.substringBefore('.')}.$secret", now)
    durable.meta().put(DurableMeta(META_SYNC_ROTATION, SYNC_JSON.encodeToString(pending)))
    recoverTokenRotation(durable, session)
}

suspend fun syncNow(
    durable: DurableDb,
    derived: DerivedDb,
    session: SyncSession,
    now: Long = System.currentTimeMillis(),
    /** This member's دارایی to share, or null when sharing is off. */
    assets: List<AssetShareItem>? = null,
): SyncResult = withFamilySync {
    val stored = loadSession(durable) ?: return@withFamilySync SyncResult(0, 0)
    if (!sameHouseholdSession(session, stored)) return@withFamilySync SyncResult(0, 0)
    val active = recoverTokenRotation(durable, stored)
    registerIdentity(active, durable)
    val outgoing = outgoingRecords(durable, derived, active, now, assets)
    var sent = 0
    for (chunk in outgoing.chunked(200)) {
        val response = request(
            "${active.base}/v1/sync",
            "POST",
            active.token,
            SYNC_JSON.encodeToString(PushBody(chunk.map { it.wire })),
        )
        // The server clamps far-future stamps and answers with what it stored; the publication
        // marks take the server's word so the next nextStamp builds on a stamp that can win.
        val clamped = runCatching { SYNC_JSON.decodeFromString<PushAck>(response) }
            .getOrNull()?.clamped?.associate { it.id to it.updatedAt }.orEmpty()
        val publications = chunk.mapNotNull { prepared ->
            prepared.publication?.let { pub -> clamped[pub.id]?.let { pub.copy(updatedAt = it) } ?: pub }
        }
        if (publications.isNotEmpty()) durable.syncPublications().putAll(publications)
        sent += chunk.size
    }

    var cursor = durable.meta().get(META_SYNC_SEQ)?.toLongOrNull() ?: 0L
    var received = 0
    var canRotate = false
    do {
        val pulled = SYNC_JSON.decodeFromString<PullBody>(
            request("${active.base}/v1/sync?since=$cursor&limit=1000", "GET", active.token, null)
        )
        val previous = cursor
        canRotate = pulled.rotationClientSecret
        val nextCursor = maxOf(cursor, pulled.seq)
        received += durable.withTransaction {
            var applied = 0
            for (record in pulled.records) {
                // The client half of the skew bound, in one choke point: everything downstream
                // compares and stores the clamped stamp.
                val bounded = record.copy(updatedAt = clampSyncStamp(record.updatedAt, now))
                if (applyRecord(durable, session, bounded, now)) applied++
            }
            durable.meta().put(DurableMeta(META_SYNC_SEQ, nextCursor.toString()))
            if (pulled.primaryMemberId.isNotBlank()) {
                durable.meta().put(DurableMeta(META_SYNC_PRIMARY, pulled.primaryMemberId))
            }
            applied
        }
        cursor = nextCursor
        val more = pulled.records.size >= 1000 && cursor > previous
    } while (more)
    if (canRotate) rotateTokenIfStale(durable, active, now)
    SyncResult(sent, received)
}
