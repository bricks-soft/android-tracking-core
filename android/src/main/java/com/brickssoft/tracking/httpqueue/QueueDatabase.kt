package com.brickssoft.tracking.httpqueue

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class DrainOwner {
    val mutex = Mutex()
    @Volatile var active: Pair<String, Job>? = null
}

/** One default app process only. Close after all users of this handle have stopped. */
class QueueDatabase(
    context: Context,
    databaseName: String,
    internal val clock: Clock = Clock.SYSTEM,
    internal val diagnostics: Diagnostics = Diagnostics.NONE,
    internal val credentialCipher: CredentialCipher = KeystoreCredentialCipher(),
) : SQLiteOpenHelper(context.applicationContext, path(context, databaseName), null, QueueSchema.VERSION), java.io.Closeable {
    internal val owner = synchronized(owners) {
        val key = path(context, databaseName)
        owners[key] ?: DrainOwner().also { owners[key] = it }
    }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) { QueueSchema.createV1(db); QueueSchema.upgrade(db, 1) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { QueueSchema.upgrade(db, oldVersion) }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper rolls this transaction back. Preserve a future schema without uploading.
        throw SQLiteException("Unsupported newer queue schema")
    }

    internal fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    fun register(config: QueueConfig) {
        config.validate()
        transaction { db ->
            val existing = config(db, config.queueId, config.revision)
            if (existing != null) {
                require(existing == config) { "Config revisions are immutable" }
                return@transaction
            }
            val max = scalar(db, "SELECT COALESCE(MAX(config_revision),0) FROM queue_config WHERE queue_id=?", config.queueId)
            require(config.revision > max) { "Config revision must increase" }
            val auth = AuthorizationStore(this).snapshot(db, config.scopeKey)
            validateHeaders(config.headers, config.reservedHeaders() + auth?.headers.orEmpty().keys.map { it.headerKey() })
            db.insertOrThrow("queue_config", null, configValues(config))
            db.execSQL("""INSERT OR IGNORE INTO queue_state(queue_id,scope_key,active_config_revision)
                VALUES(?,?,?)""", arrayOf<Any>(config.queueId, config.scopeKey, config.revision))
            db.execSQL("UPDATE queue_state SET active_config_revision=? WHERE queue_id=? AND scope_key=?",
                arrayOf<Any>(config.revision, config.queueId, config.scopeKey))
        }
    }

    fun definitions(): List<QueueConfig> = readableDatabase.rawQuery("""SELECT c.* FROM queue_config c
        JOIN queue_state s ON c.queue_id=s.queue_id AND c.config_revision=s.active_config_revision
        ORDER BY c.queue_id,c.scope_key""", null).use { it.collect(::readConfig) }

    fun state(queueId: String, scopeKey: String): QueueState = state(readableDatabase, queueId, scopeKey)

    internal fun state(db: SQLiteDatabase, queueId: String, scopeKey: String): QueueState =
        db.rawQuery("SELECT * FROM queue_state WHERE queue_id=? AND scope_key=?", arrayOf(queueId, scopeKey)).use {
            require(it.moveToFirst()) { "Unknown queue scope" }
            QueueState(queueId, scopeKey, it.long("active_config_revision"), UploadGate.valueOf(it.string("upload_gate")),
                it.int("attempts"), it.long("next_attempt_at"), it.nullableString("last_error"))
        }

    internal fun config(db: SQLiteDatabase, queueId: String, revision: Long): QueueConfig? =
        db.rawQuery("SELECT * FROM queue_config WHERE queue_id=? AND config_revision=?", arrayOf(queueId, revision.toString())).use {
            if (it.moveToFirst()) readConfig(it) else null
        }

    internal fun insert(record: QueueRecord): EnqueueResult {
        val result = transaction { db ->
            var pruned = prune(db)
            val config = requireNotNull(config(db, record.queueId, record.configRevision)) { "Unknown config revision" }
            require(config.scopeKey == record.scopeKey && record.uuid.isNotBlank() && record.capturedAt >= 0)
            config.validate()
            QueueJson.obj(record.payloadJson)
            validateRecord(db, config, record)
            val existing = db.rawQuery("SELECT * FROM queue_item WHERE uuid=?", arrayOf(record.uuid)).use {
                if (it.moveToFirst()) readItem(it) else null
            }
            if (existing != null) {
                require(existing.record == record) { "UUID already belongs to different data" }
                return@transaction EnqueueResult(record.uuid, false, true, pruned)
            }
            val values = ContentValues().apply {
                put("queue_id", record.queueId); put("scope_key", record.scopeKey); put("uuid", record.uuid)
                put("captured_at", record.capturedAt); put("enqueued_at", clock.now())
                put("expires_at", config.retention.expiresAt(record.capturedAt)); put("config_revision", record.configRevision)
                put("payload_json", record.payloadJson); put("request_json", record.request?.let(QueueJson::request))
            }
            db.insertOrThrow("queue_item", null, values)
            pruned += prune(db)
            EnqueueResult(record.uuid, true, scalar(db, "SELECT COUNT(*) FROM queue_item WHERE uuid=?", record.uuid) > 0, pruned)
        }
        reportPrune(result.pruned)
        return result
    }

    private fun validateRecord(db: SQLiteDatabase, config: QueueConfig, record: QueueRecord) {
        when (config.encoderKind) {
            EncoderKind.JSON_RECORD_BATCH -> require(record.request == null) { "Batch records cannot override routing" }
            EncoderKind.SINGLE_REQUEST -> {
                val request = requireNotNull(record.request) { "Rendered request required" }
                validateDestination(request.url, config.allowCleartext)
                require(sameOrigin(request.url, config.endpoint)) { "Request destination must match config origin" }
                validateRequest(request)
                val auth = AuthorizationStore(this).snapshot(db, config.scopeKey)
                validateHeaders(request.headers, config.reservedHeaders() + auth?.headers.orEmpty().keys.map { it.headerKey() })
            }
        }
    }

    internal fun count(queueId: String?, scopeKey: String?): CountResult = transaction { db ->
        val pruned = prune(db)
        CountResult(countRaw(db, queueId, scopeKey), pruned)
    }.also { reportPrune(it.pruned) }

    internal fun countRaw(db: SQLiteDatabase, queueId: String?, scopeKey: String?): Int {
        val (where, args) = filter(queueId, scopeKey)
        return scalar(db, "SELECT COUNT(*) FROM queue_item WHERE $where", *args).toInt()
    }

    internal fun purge(queueId: String, scopeKey: String, uuids: Set<String>?): Int = transaction { db ->
        val deleted = if (uuids == null) db.delete("queue_item", "queue_id=? AND scope_key=?", arrayOf(queueId, scopeKey))
        else uuids.sumOf { db.delete("queue_item", "queue_id=? AND scope_key=? AND uuid=?", arrayOf(queueId, scopeKey, it)) }
        cleanRemovedBlocks(db)
        deleted
    }

    internal fun prune(db: SQLiteDatabase): PruneCounts {
        val expired = db.delete("queue_item", "expires_at<=?", arrayOf(clock.now().toString()))
        var overflow = 0
        val cursor = db.rawQuery("""SELECT c.* FROM queue_config c JOIN queue_state s
            ON c.queue_id=s.queue_id AND c.config_revision=s.active_config_revision""", null)
        val configs = try { cursor.collect(::readConfig) } finally { cursor.close() }
        for (config in configs) {
            val cap = config.retention.maxRecordsToPersist
            if (cap >= 0) {
                val excess = countRaw(db, config.queueId, config.scopeKey) - cap
                if (excess > 0) overflow += db.delete("queue_item", """id IN (SELECT id FROM queue_item
                    WHERE queue_id=? AND scope_key=? ORDER BY captured_at,id LIMIT ?)""",
                    arrayOf(config.queueId, config.scopeKey, excess.toString()))
            }
        }
        cleanRemovedBlocks(db)
        return PruneCounts(expired, overflow)
    }

    private fun cleanRemovedBlocks(db: SQLiteDatabase) {
        db.execSQL("""UPDATE queue_state SET upload_gate='OPEN',last_error=NULL,blocked_item_id=NULL,
            attempts=0,next_attempt_at=0,batch_limit=NULL WHERE upload_gate='BLOCKED' AND blocked_item_id IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM queue_item WHERE id=queue_state.blocked_item_id)""")
        // Active definitions stay available to workers even when empty.
    }

    internal fun select(db: SQLiteDatabase, queueId: String?, scopeKey: String?, limit: Int): List<StoredItem> {
        val (where, args) = filter(queueId, scopeKey)
        return db.rawQuery("SELECT * FROM queue_item WHERE $where ORDER BY captured_at,id LIMIT $limit", args).use {
            it.collect(::readItem)
        }
    }

    internal fun acknowledge(items: List<StoredItem>): Int = transaction { db ->
        val deleted = items.sumOf { db.delete("queue_item", "id=? AND uuid=?", arrayOf(it.id.toString(), it.record.uuid)) }
        val first = items.first().record
        db.execSQL("UPDATE queue_state SET attempts=0,next_attempt_at=0 WHERE queue_id=? AND scope_key=?",
            arrayOf(first.queueId, first.scopeKey))
        cleanRemovedBlocks(db)
        deleted
    }

    internal fun setGate(db: SQLiteDatabase, item: StoredItem, gate: UploadGate, reason: String) {
        db.execSQL("""UPDATE queue_state SET upload_gate=?,last_error=?,blocked_item_id=?
            WHERE queue_id=? AND scope_key=? AND upload_gate='OPEN'""",
            arrayOf<Any?>(gate.name, reason, if (gate == UploadGate.BLOCKED) item.id else null, item.record.queueId, item.record.scopeKey))
    }

    internal fun retry(item: StoredItem, attempts: Int, due: Long, reason: String) = transaction { db ->
        db.execSQL("""UPDATE queue_state SET attempts=?,next_attempt_at=?,last_error=?
            WHERE queue_id=? AND scope_key=? AND upload_gate='OPEN'""",
            arrayOf<Any>(attempts, due, reason, item.record.queueId, item.record.scopeKey))
    }

    internal fun batchLimit(db: SQLiteDatabase, item: StoredItem): Int? =
        db.rawQuery("SELECT batch_limit FROM queue_state WHERE queue_id=? AND scope_key=?",
            arrayOf(item.record.queueId, item.record.scopeKey)).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getInt(0)
        }

    internal fun split(item: StoredItem, limit: Int) = transaction { db ->
        db.execSQL("UPDATE queue_state SET batch_limit=? WHERE queue_id=? AND scope_key=?",
            arrayOf<Any>(limit, item.record.queueId, item.record.scopeKey))
    }

    internal fun reportPrune(counts: PruneCounts) {
        if (counts != PruneCounts()) diagnostics.emit(DiagnosticEvent("RETENTION", pruned = counts))
    }

    companion object {
        private val owners = mutableMapOf<String, DrainOwner>()
        internal fun path(context: Context, name: String): String {
            require(name.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Use a simple database name" }
            return File(context.noBackupFilesDir, "httpqueue-$name.db").canonicalPath
        }
    }
}

internal fun scalar(db: SQLiteDatabase, sql: String, vararg args: String): Long {
    val cursor = db.rawQuery(sql, args)
    try { check(cursor.moveToFirst()); return cursor.getLong(0) }
    finally { cursor.close() }
}
internal fun filter(queueId: String?, scopeKey: String?): Pair<String, Array<String>> {
    val clauses = mutableListOf("1=1"); val args = mutableListOf<String>()
    if (queueId != null) { clauses += "queue_id=?"; args += queueId }
    if (scopeKey != null) { clauses += "scope_key=?"; args += scopeKey }
    return clauses.joinToString(" AND ") to args.toTypedArray()
}
internal fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
internal fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun <T> Cursor.collect(read: (Cursor) -> T): List<T> = buildList { while (moveToNext()) add(read(this@collect)) }

private fun readItem(c: Cursor) = StoredItem(c.long("id"), QueueRecord(c.string("queue_id"), c.string("scope_key"),
    c.long("config_revision"), c.long("captured_at"), c.string("payload_json"),
    c.nullableString("request_json")?.let(QueueJson::request), c.string("uuid")), c.long("enqueued_at"), c.long("expires_at"))

private fun configValues(c: QueueConfig) = ContentValues().apply {
    put("queue_id", c.queueId); put("config_revision", c.revision); put("schema_version", c.schemaVersion)
    put("scope_key", c.scopeKey); put("endpoint", c.endpoint); put("method", c.method)
    put("headers_json", QueueJson.encodeMap(c.headers)); put("params_json", c.paramsJson); put("root_property", c.rootProperty)
    put("encoder_kind", c.encoderKind.name); put("auth_required", if (c.authRequired) 1 else 0)
    put("retention_json", JSONObject().put("maxDaysToPersist", c.retention.maxDaysToPersist)
        .put("maxRecordsToPersist", c.retention.maxRecordsToPersist).toString())
    put("options_json", JSONObject().put("autoSync", c.autoSync).put("autoSyncThreshold", c.autoSyncThreshold)
        .put("maxBatchSize", c.maxBatchSize).put("maxBatchAgeSeconds", c.maxBatchAgeSeconds)
        .put("timeoutMillis", c.timeoutMillis).put("allowCleartext", c.allowCleartext)
        .put("credentialHeaderNames", JSONArray(c.credentialHeaderNames.toList())).toString())
}

private fun readConfig(c: Cursor): QueueConfig {
    val retention = QueueJson.obj(c.string("retention_json")); val options = QueueJson.obj(c.string("options_json"))
    val names = options.optJSONArray("credentialHeaderNames") ?: JSONArray()
    return QueueConfig(c.string("queue_id"), c.string("scope_key"), c.long("config_revision"), c.string("endpoint"),
        c.string("method"), QueueJson.map(c.string("headers_json")), c.string("params_json"), c.string("root_property"),
        EncoderKind.valueOf(c.string("encoder_kind")), c.int("auth_required") != 0,
        options.optBoolean("autoSync", true), options.optInt("autoSyncThreshold", 2), options.optInt("maxBatchSize", 50),
        options.optLong("maxBatchAgeSeconds", 0), options.optLong("timeoutMillis", 60_000), options.optBoolean("allowCleartext", false),
        RetentionPolicy(retention.optInt("maxDaysToPersist", 1), retention.optInt("maxRecordsToPersist", -1)),
        (0 until names.length()).map { names.getString(it) }.toSet(), c.int("schema_version"))
}
