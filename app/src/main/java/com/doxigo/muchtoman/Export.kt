package com.doxigo.muchtoman

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * The backup file — one `.mtbak`, holding everything a lost phone would otherwise take with it.
 *
 * `durable.db` is the only copy of her messages and decisions once the inbox has pruned, and
 * Android backup is switched off on purpose, so this file is the entire recovery story. The shape:
 *
 * ```
 * "MTBAK1" · 4-byte big-endian header length · header JSON · ciphertext
 * ```
 *
 * The header is plaintext — it has to be, it carries the salt and IV — but it is fed to GCM as
 * associated data, so a file whose header has been fiddled with fails authentication exactly as a
 * fiddled ciphertext does. The ciphertext is AES-256-GCM over a gzip of one JSON payload: gzip
 * because the payload is mostly stored SMS bodies, which compress to a fraction of themselves.
 *
 * The key is PBKDF2-HMAC-SHA256 from a passphrase she chooses in the moment. Deliberately not the
 * app lock and not a keystore key: a backup that only the dead phone could decrypt would be a
 * ritual, not a recovery. Neither the passphrase nor the derived key is ever written anywhere.
 */
const val BACKUP_MAGIC = "MTBAK1"
const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_KDF_ALGO = "PBKDF2WithHmacSHA256"
const val BACKUP_CIPHER_ALGO = "AES/GCM/NoPadding"
const val BACKUP_KDF_ITERATIONS = 600_000
const val BACKUP_MIN_PASSPHRASE = 6

/** Past this a header is garbage, not a header — read before anything is trusted. */
private const val MAX_HEADER_BYTES = 64 * 1024

/** A file demanding more work than this is a denial of service, not a backup. */
private const val MAX_KDF_ITERATIONS = 10_000_000

/**
 * The most a payload may inflate to. The hard cap on stored messages keeps a real database around
 * a few MB, so this is two orders of magnitude of slack — anything past it is a zip bomb.
 */
private const val MAX_PAYLOAD_BYTES = 256 * 1024 * 1024

private val BACKUP_JSON = Json { ignoreUnknownKeys = true }

@Serializable
data class BackupKdf(val algo: String, val iterations: Int, val saltB64: String)

@Serializable
data class BackupCipher(val algo: String, val ivB64: String)

@Serializable
data class BackupHeader(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersionCode: Int = 0,
    val kdf: BackupKdf,
    val cipher: BackupCipher,
)

/**
 * One preference, typed. SharedPreferences stores real Booleans and Longs, and writing one back
 * as the wrong type crashes the next `getBoolean` — so the type rides beside the value instead of
 * being guessed from its spelling. `t` is s/b/i/l; a tag this build does not know is skipped on
 * restore rather than misread.
 */
@Serializable
data class BackupPref(val t: String, val v: String)

/** The plaintext, before gzip and the cipher: her preferences, and the durable database whole. */
@Serializable
data class BackupPayload(
    val prefs: Map<String, BackupPref> = emptyMap(),
    val durableDbB64: String = "",
)

/** What [openBackup] hands back: the payload, plus the header for words like «پشتیبان ۳ مرداد». */
data class OpenedBackup(val header: BackupHeader, val payload: BackupPayload)

enum class BackupFault {
    /** Wrong magic, torn envelope, malformed header — this was never one of our files. */
    NOT_A_BACKUP,

    /** A well-formed backup from a build newer than this one. Updating the app is the fix. */
    NEWER_FORMAT,

    /** GCM refused it. The two causes are indistinguishable by design, and the words say both. */
    WRONG_PASSPHRASE_OR_CORRUPT,
}

class BackupException(val fault: BackupFault, message: String) : Exception(message)

private fun fail(fault: BackupFault, message: String): Nothing =
    throw BackupException(fault, message)

/** Everything into one encrypted envelope. [iterations] and [formatVersion] bend only in tests. */
fun sealBackup(
    payload: BackupPayload,
    passphrase: String,
    createdAt: Long,
    appVersionCode: Int,
    iterations: Int = BACKUP_KDF_ITERATIONS,
    formatVersion: Int = BACKUP_FORMAT_VERSION,
    random: SecureRandom = SecureRandom(),
): ByteArray {
    require(passphrase.length >= BACKUP_MIN_PASSPHRASE) { "passphrase too short" }
    val salt = ByteArray(16).also(random::nextBytes)
    val iv = ByteArray(12).also(random::nextBytes)
    val header = BackupHeader(
        formatVersion = formatVersion,
        createdAt = createdAt,
        appVersionCode = appVersionCode,
        kdf = BackupKdf(BACKUP_KDF_ALGO, iterations, Base64.encode(salt)),
        cipher = BackupCipher(BACKUP_CIPHER_ALGO, Base64.encode(iv)),
    )
    val headerBytes = BACKUP_JSON.encodeToString(header).toByteArray(Charsets.UTF_8)
    val key = deriveBackupKey(passphrase.toCharArray(), salt, iterations)
    val cipher = Cipher.getInstance(BACKUP_CIPHER_ALGO)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    // The header rides outside the encryption, so it rides inside the authentication.
    cipher.updateAAD(headerBytes)
    val sealed = cipher.doFinal(gzip(BACKUP_JSON.encodeToString(payload).toByteArray(Charsets.UTF_8)))

    val out = ByteArrayOutputStream(BACKUP_MAGIC.length + 4 + headerBytes.size + sealed.size)
    out.write(BACKUP_MAGIC.toByteArray(Charsets.US_ASCII))
    out.write(int32be(headerBytes.size))
    out.write(headerBytes)
    out.write(sealed)
    return out.toByteArray()
}

/**
 * The envelope back apart, with every refusal named. Order matters: everything that can be judged
 * without the passphrase is judged first, so «رمز اشتباهه» is never said about a file that was
 * never a backup at all.
 */
fun openBackup(bytes: ByteArray, passphrase: String): OpenedBackup {
    val magic = BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)
    if (bytes.size < magic.size + 4) fail(BackupFault.NOT_A_BACKUP, "too short")
    if (!magic.indices.all { bytes[it] == magic[it] }) fail(BackupFault.NOT_A_BACKUP, "bad magic")

    val headerLen = int32be(bytes, magic.size)
    if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) fail(BackupFault.NOT_A_BACKUP, "bad header length")
    val headerFrom = magic.size + 4
    // GCM's tag alone is 16 bytes; anything shorter cannot hold even an empty payload.
    if (bytes.size < headerFrom + headerLen + 16) fail(BackupFault.NOT_A_BACKUP, "truncated")

    val headerBytes = bytes.copyOfRange(headerFrom, headerFrom + headerLen)
    val header = runCatching {
        BACKUP_JSON.decodeFromString<BackupHeader>(String(headerBytes, Charsets.UTF_8))
    }.getOrElse { fail(BackupFault.NOT_A_BACKUP, "unreadable header") }

    if (header.formatVersion > BACKUP_FORMAT_VERSION) fail(BackupFault.NEWER_FORMAT, "format ${header.formatVersion}")
    if (header.formatVersion < 1) fail(BackupFault.NOT_A_BACKUP, "bad format version")
    if (header.kdf.algo != BACKUP_KDF_ALGO) fail(BackupFault.NOT_A_BACKUP, "unknown kdf")
    if (header.cipher.algo != BACKUP_CIPHER_ALGO) fail(BackupFault.NOT_A_BACKUP, "unknown cipher")
    if (header.kdf.iterations !in 1..MAX_KDF_ITERATIONS) fail(BackupFault.NOT_A_BACKUP, "bad iterations")
    val salt = runCatching { Base64.decode(header.kdf.saltB64) }
        .getOrElse { fail(BackupFault.NOT_A_BACKUP, "bad salt") }
    if (salt.size !in 8..64) fail(BackupFault.NOT_A_BACKUP, "bad salt size")
    val iv = runCatching { Base64.decode(header.cipher.ivB64) }
        .getOrElse { fail(BackupFault.NOT_A_BACKUP, "bad iv") }
    if (iv.size != 12) fail(BackupFault.NOT_A_BACKUP, "bad iv size")

    // From here down every failure is one answer on purpose: GCM cannot tell a wrong key from a
    // flipped bit, and pretending otherwise would be inventing a diagnosis.
    val payload = runCatching {
        val key = deriveBackupKey(passphrase.toCharArray(), salt, header.kdf.iterations)
        val cipher = Cipher.getInstance(BACKUP_CIPHER_ALGO)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(headerBytes)
        val plain = cipher.doFinal(bytes, headerFrom + headerLen, bytes.size - headerFrom - headerLen)
        BACKUP_JSON.decodeFromString<BackupPayload>(String(gunzip(plain), Charsets.UTF_8))
    }.getOrElse { fail(BackupFault.WRONG_PASSPHRASE_OR_CORRUPT, "auth failed") }
    return OpenedBackup(header, payload)
}

/** The staged half of a restore is this same JSON, written to disk for the next launch to apply. */
fun encodeBackupPrefs(prefs: Map<String, BackupPref>): String = BACKUP_JSON.encodeToString(prefs)

fun decodeBackupPrefs(json: String): Map<String, BackupPref> = BACKUP_JSON.decodeFromString(json)

/**
 * PBKDF2-HMAC-SHA256, 256-bit. The platform factory where it exists; below API 26 the platform
 * only ships the SHA1 flavour, so the same function is computed by hand — [pbkdf2HmacSha256] is
 * pinned against the platform's answer in the tests, which is what makes the fallback safe to
 * trust with the only copy of her data.
 */
internal fun deriveBackupKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray =
    runCatching {
        SecretKeyFactory.getInstance(BACKUP_KDF_ALGO)
            .generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded
    }.getOrElse { pbkdf2HmacSha256(passphrase, salt, iterations, 32) }

/** RFC 8018, section 5.2 — passphrase as UTF-8, exactly as the JCA factory encodes it. */
internal fun pbkdf2HmacSha256(
    passphrase: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyBytes: Int,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(String(passphrase).toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val out = ByteArray(keyBytes)
    var written = 0
    var block = 1
    while (written < keyBytes) {
        mac.update(salt)
        var u = mac.doFinal(int32be(block))
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        val take = minOf(t.size, keyBytes - written)
        System.arraycopy(t, 0, out, written, take)
        written += take
        block++
    }
    return out
}

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 4 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

/** Capped: the size of the inflated result is attacker-chosen, and the phone's memory is not. */
internal fun gunzip(bytes: ByteArray, maxBytes: Int = MAX_PAYLOAD_BYTES): ByteArray {
    val out = ByteArrayOutputStream(minOf(bytes.size * 4, 1 shl 20))
    GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = gz.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) error("payload too large")
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

private fun int32be(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

private fun int32be(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xff) shl 24) or
        ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or
        (bytes[at + 3].toInt() and 0xff)
