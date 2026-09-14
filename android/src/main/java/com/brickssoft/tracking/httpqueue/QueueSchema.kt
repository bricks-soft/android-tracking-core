package com.brickssoft.tracking.httpqueue

import android.database.sqlite.SQLiteDatabase

internal object QueueSchema {
    const val VERSION = 2
    fun createV1(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE queue_config (
            queue_id TEXT NOT NULL, config_revision INTEGER NOT NULL, schema_version INTEGER NOT NULL,
            scope_key TEXT NOT NULL, endpoint TEXT NOT NULL, method TEXT NOT NULL,
            headers_json TEXT NOT NULL, params_json TEXT NOT NULL, root_property TEXT NOT NULL,
            encoder_kind TEXT NOT NULL, auth_required INTEGER NOT NULL, retention_json TEXT NOT NULL,
            PRIMARY KEY(queue_id, config_revision))""")
        db.execSQL("""CREATE TABLE queue_state (
            queue_id TEXT NOT NULL, scope_key TEXT NOT NULL, active_config_revision INTEGER NOT NULL,
            upload_gate TEXT NOT NULL DEFAULT 'OPEN', attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL DEFAULT 0, last_error TEXT,
            PRIMARY KEY(queue_id, scope_key))""")
        db.execSQL("""CREATE TABLE queue_item (
            id INTEGER PRIMARY KEY AUTOINCREMENT, queue_id TEXT NOT NULL, scope_key TEXT NOT NULL,
            uuid TEXT UNIQUE NOT NULL, captured_at INTEGER NOT NULL, enqueued_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, config_revision INTEGER NOT NULL,
            payload_json TEXT NOT NULL, request_json TEXT,
            FOREIGN KEY(queue_id, config_revision) REFERENCES queue_config(queue_id, config_revision))""")
        db.execSQL("CREATE INDEX queue_item_order ON queue_item(queue_id, scope_key, captured_at, id)")
        db.execSQL("CREATE INDEX queue_item_expiry ON queue_item(expires_at)")
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE queue_config ADD COLUMN options_json TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE queue_state ADD COLUMN drain_started INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE queue_state ADD COLUMN batch_limit INTEGER")
            db.execSQL("ALTER TABLE queue_state ADD COLUMN blocked_item_id INTEGER")
            db.execSQL("""CREATE TABLE queue_authorization (
                scope_key TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL,
                headers_ciphertext BLOB, expires_at INTEGER)""")
        }
    }
}
