package com.brickssoft.tracking.httpqueue

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are encrypted separately from immutable request rows. Revisions survive clear(). */
data class Authorization(
    val scopeKey: String,
    val revision: Long,
    val headers: Map<String, String>,
    val expiresAtEpochMs: Long? = null,
) {
    override fun toString(): String = "Authorization(scopeKey=$scopeKey, revision=$revision, headers=[REDACTED])"
}

interface CredentialCipher {
    fun encrypt(scopeKey: String, plaintext: ByteArray): ByteArray
    fun decrypt(scopeKey: String, ciphertext: ByteArray): ByteArray
}

/** Android Keystore AES/GCM; queue file lives under noBackupFilesDir. See dated sources in README. */
class KeystoreCredentialCipher : CredentialCipher {
    override fun encrypt(scopeKey: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(scopeKey.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
    }

    override fun decrypt(scopeKey: String, ciphertext: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(ciphertext)
        val ivSize = buffer.int
        require(ivSize in 12..32 && buffer.remaining() > ivSize)
        val iv = ByteArray(ivSize).also { buffer.get(it) }
        val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(scopeKey.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "tracking.httpqueue.credentials.v1"
        val lock = Any()
    }
}

class AuthorizationStore(private val database: QueueDatabase) {
    /** False means an equal or stale revision; it never rewrites credentials or opens a gate. */
    fun setAuthorization(value: Authorization): Boolean {
        require(value.scopeKey.isNotBlank() && value.revision > 0)
        require(value.expiresAtEpochMs == null || value.expiresAtEpochMs > 0)
        validateHeaders(value.headers)
        return database.transaction { db ->
            if (value.revision <= revision(db, value.scopeKey)) return@transaction false
            rejectPersistedCredentials(db, value)
            val values = ContentValues().apply {
                put("scope_key", value.scopeKey); put("revision", value.revision)
                put("headers_ciphertext", database.credentialCipher.encrypt(value.scopeKey, QueueJson.encodeMap(value.headers).toByteArray(Charsets.UTF_8)))
                put("expires_at", value.expiresAtEpochMs)
            }
            db.insertWithOnConflict("queue_authorization", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.execSQL("""UPDATE queue_state SET upload_gate='OPEN',attempts=0,next_attempt_at=0,last_error=NULL
                WHERE scope_key=? AND upload_gate='AUTH_PAUSED'""", arrayOf(value.scopeKey))
            true
        }
    }

    fun snapshot(scopeKey: String): Authorization? = snapshot(database.readableDatabase, scopeKey)

    internal fun snapshot(db: SQLiteDatabase, scopeKey: String): Authorization? =
        db.rawQuery("SELECT * FROM queue_authorization WHERE scope_key=?", arrayOf(scopeKey)).use {
            if (!it.moveToFirst() || it.isNull(it.getColumnIndexOrThrow("headers_ciphertext"))) return@use null
            val plaintext = database.credentialCipher.decrypt(scopeKey, it.getBlob(it.getColumnIndexOrThrow("headers_ciphertext")))
            val expires = it.getColumnIndexOrThrow("expires_at")
            Authorization(scopeKey, it.long("revision"), QueueJson.map(plaintext.toString(Charsets.UTF_8)),
                if (it.isNull(expires)) null else it.getLong(expires))
        }

    /** Persist the fence before canceling a possible in-flight call; wait for the drain owner to exit. */
    suspend fun clear(scopeKey: String) {
        database.transaction { db ->
            val next = addWithoutOverflow(revision(db, scopeKey), 1L)
            db.execSQL("""INSERT OR REPLACE INTO queue_authorization(scope_key,revision,headers_ciphertext)
                VALUES(?,?,NULL)""", arrayOf<Any>(scopeKey, next))
            db.execSQL("UPDATE queue_state SET upload_gate='PAUSED' WHERE scope_key=?", arrayOf(scopeKey))
        }
        database.owner.active?.takeIf { it.first == scopeKey }?.second?.cancel()
        database.owner.mutex.withLock { }
    }

    internal fun revision(db: SQLiteDatabase, scopeKey: String): Long =
        scalar(db, "SELECT COALESCE(MAX(revision),0) FROM queue_authorization WHERE scope_key=?", scopeKey)

    internal fun pauseIfCurrent(item: StoredItem, sentRevision: Long): Boolean = database.transaction { db ->
        if (revision(db, item.record.scopeKey) != sentRevision) return@transaction false
        database.setGate(db, item, UploadGate.AUTH_PAUSED, "AUTH_REQUIRED")
        true
    }

    private fun rejectPersistedCredentials(db: SQLiteDatabase, value: Authorization) {
        val names = value.headers.keys.map { it.headerKey() }.toSet()
        val configCursor = db.rawQuery("SELECT headers_json FROM queue_config WHERE scope_key=?", arrayOf(value.scopeKey))
        try {
            while (configCursor.moveToNext()) validateHeaders(QueueJson.map(configCursor.getString(0)), names)
        } finally { configCursor.close() }
        val requestCursor = db.rawQuery("SELECT request_json FROM queue_item WHERE scope_key=? AND request_json IS NOT NULL", arrayOf(value.scopeKey))
        try {
            while (requestCursor.moveToNext()) validateHeaders(QueueJson.request(requestCursor.getString(0)).headers, names)
        } finally { requestCursor.close() }
    }
}
