package io.github.zhyuzh3d.hermit.registry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.zhyuzh3d.hermit.model.CodeRelease
import io.github.zhyuzh3d.hermit.model.DeliveryMode
import io.github.zhyuzh3d.hermit.model.WebAppInstance
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppRegistry(context: Context) : SQLiteOpenHelper(context, "hermit-registry.sqlite", null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE instances (
              app_id TEXT PRIMARY KEY, name TEXT NOT NULL,
              mode TEXT NOT NULL CHECK(mode IN ('ONLINE','LOCAL')),
              start_url TEXT NOT NULL, primary_origin TEXT NOT NULL,
              web_profile_name TEXT NOT NULL UNIQUE, trust_revision INTEGER NOT NULL,
              active_release_id TEXT, active_data_generation TEXT NOT NULL,
              source_adapter TEXT NOT NULL, source_spec TEXT NOT NULL,
              developer_enabled INTEGER NOT NULL DEFAULT 0,
              state TEXT NOT NULL DEFAULT 'ready',
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE releases (
              release_id TEXT PRIMARY KEY,
              app_id TEXT NOT NULL REFERENCES instances(app_id) ON DELETE CASCADE,
              tree_hash TEXT NOT NULL, provenance TEXT NOT NULL,
              version_code INTEGER, version_name TEXT, source_revision TEXT,
              entry_path TEXT NOT NULL DEFAULT 'index.html',
              relative_root TEXT NOT NULL, created_at INTEGER NOT NULL,
              UNIQUE(app_id, tree_hash)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX releases_by_app_time ON releases(app_id, created_at DESC)")
        db.execSQL("""
            CREATE TABLE grants (
              app_id TEXT NOT NULL REFERENCES instances(app_id) ON DELETE CASCADE,
              trust_revision INTEGER NOT NULL, capability TEXT NOT NULL,
              resource_scope TEXT NOT NULL DEFAULT '',
              decision TEXT NOT NULL CHECK(decision IN ('allow','deny')),
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(app_id, trust_revision, capability, resource_scope)
            )
        """.trimIndent())
        createSystemPermissionObservations(db)
        db.execSQL("""
            CREATE TABLE operations (
              operation_id TEXT PRIMARY KEY, app_id TEXT, kind TEXT NOT NULL,
              state TEXT NOT NULL, idempotency_key TEXT, input_hash TEXT,
              expected_release_id TEXT, result_release_id TEXT,
              error_code TEXT, error_message TEXT,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE diagnostic_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT, app_id TEXT, session_id TEXT,
              method TEXT NOT NULL, decision TEXT NOT NULL, result_code TEXT,
              duration_ms INTEGER, created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE profile_cleanup (
              profile_name TEXT PRIMARY KEY, app_id TEXT NOT NULL,
              state TEXT NOT NULL DEFAULT 'pending',
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE releases ADD COLUMN entry_path TEXT NOT NULL DEFAULT 'index.html'")
        }
        if (oldVersion < 3) createSystemPermissionObservations(db)
        if (newVersion > VERSION) throw IllegalStateException("Unsupported registry version $newVersion")
    }

    fun listInstances(): List<WebAppInstance> =
        readableDatabase.query("instances", null, "state = ?", arrayOf("ready"), null, null, "created_at ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toInstance()) } }

    fun getInstance(appId: String): WebAppInstance? =
        readableDatabase.query("instances", null, "app_id = ? AND state = ?", arrayOf(appId, "ready"), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }

    fun insertInstance(instance: WebAppInstance) {
        writableDatabase.insertOrThrow("instances", null, instance.values())
    }

    fun insertLocalWithRelease(instance: WebAppInstance, release: CodeRelease) {
        require(instance.appId == release.appId)
        writableDatabase.inTransaction {
            insertOrThrow("instances", null, instance.values())
            insertOrThrow("releases", null, release.values())
            val values = ContentValues().apply {
                put("active_release_id", release.releaseId)
                put("updated_at", System.currentTimeMillis())
            }
            check(update("instances", values, "app_id = ? AND active_release_id IS NULL", arrayOf(instance.appId)) == 1)
        }
    }

    fun commitRelease(release: CodeRelease, expectedActive: String?) {
        writableDatabase.inTransaction {
            insertOrThrow("releases", null, release.values())
            val origin = query("instances", arrayOf("primary_origin"), "app_id = ?", arrayOf(release.appId), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else throw IllegalStateException("App not found") }
            val values = ContentValues().apply {
                put("active_release_id", release.releaseId)
                put("start_url", "$origin/${release.entryPath}")
                put("updated_at", System.currentTimeMillis())
            }
            val where = if (expectedActive == null) "app_id = ? AND active_release_id IS NULL" else "app_id = ? AND active_release_id = ?"
            val args = if (expectedActive == null) arrayOf(release.appId) else arrayOf(release.appId, expectedActive)
            if (update("instances", values, where, args) != 1) throw IllegalStateException("Active release changed")
        }
    }

    fun getRelease(releaseId: String): CodeRelease? =
        readableDatabase.query("releases", null, "release_id = ?", arrayOf(releaseId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toRelease() else null }

    fun findReleaseByTreeHash(appId: String, treeHash: String): CodeRelease? =
        readableDatabase.query("releases", null, "app_id = ? AND tree_hash = ?", arrayOf(appId, treeHash), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toRelease() else null }

    fun listReleases(appId: String): List<CodeRelease> =
        readableDatabase.query("releases", null, "app_id = ?", arrayOf(appId), null, null, "created_at DESC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toRelease()) } }

    fun activateRelease(appId: String, releaseId: String, expectedActive: String?) {
        writableDatabase.inTransaction {
            val releaseEntry = query("releases", arrayOf("entry_path"), "app_id = ? AND release_id = ?",
                arrayOf(appId, releaseId), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: throw IllegalArgumentException("Release does not belong to app")
            val origin = query("instances", arrayOf("primary_origin"), "app_id = ?", arrayOf(appId), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else throw IllegalArgumentException("App not found") }
            val values = ContentValues().apply {
                put("active_release_id", releaseId)
                put("start_url", "$origin/$releaseEntry")
                put("updated_at", System.currentTimeMillis())
            }
            val where = if (expectedActive == null) "app_id = ? AND active_release_id IS NULL" else "app_id = ? AND active_release_id = ?"
            val args = if (expectedActive == null) arrayOf(appId) else arrayOf(appId, expectedActive)
            check(update("instances", values, where, args) == 1) { "Active release changed" }
        }
    }

    fun deleteReleaseRows(appId: String, releaseIds: Collection<String>) {
        if (releaseIds.isEmpty()) return
        writableDatabase.inTransaction {
            releaseIds.forEach { releaseId ->
                delete("releases", "app_id = ? AND release_id = ? AND release_id NOT IN (SELECT active_release_id FROM instances WHERE app_id = ?)",
                    arrayOf(appId, releaseId, appId))
            }
        }
    }

    fun updateInstance(appId: String, name: String, startUrl: String?, primaryOrigin: String?, sourceSpec: String?) {
        val current = getInstance(appId) ?: throw IllegalArgumentException("App not found")
        val originChanged = primaryOrigin != null && primaryOrigin != current.primaryOrigin
        writableDatabase.inTransaction {
            val now = System.currentTimeMillis()
            val changed = update("instances", ContentValues().apply {
                put("name", name.take(80))
                startUrl?.let { put("start_url", it) }
                primaryOrigin?.let { put("primary_origin", it) }
                sourceSpec?.let { put("source_spec", it) }
                if (originChanged) {
                    put("trust_revision", current.trustRevision + 1)
                    put("web_profile_name", "app-${UUID.randomUUID().toString().replace("-", "")}")
                }
                put("updated_at", now)
            }, "app_id = ? AND state = 'ready'", arrayOf(appId))
            check(changed == 1) { "App changed while updating" }
            if (originChanged) {
                insertWithOnConflict("profile_cleanup", null, ContentValues().apply {
                    put("profile_name", current.webProfileName); put("app_id", appId); put("state", "pending")
                    put("created_at", now); put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun updateSource(appId: String, adapter: String, sourceSpec: String) {
        writableDatabase.update("instances", ContentValues().apply {
            put("source_adapter", adapter)
            put("source_spec", sourceSpec)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
    }

    fun swapDataGeneration(appId: String, expectedGeneration: String, nextGeneration: String) {
        writableDatabase.inTransaction {
            val trustRevision = query("instances", arrayOf("trust_revision"),
                "app_id = ? AND state = 'ready' AND active_data_generation = ?",
                arrayOf(appId, expectedGeneration), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: throw IllegalStateException("Active data generation changed")
            val changed = update("instances", ContentValues().apply {
                put("active_data_generation", nextGeneration)
                put("trust_revision", trustRevision + 1)
                put("developer_enabled", 0)
                put("updated_at", System.currentTimeMillis())
            }, "app_id = ? AND state = 'ready' AND active_data_generation = ?", arrayOf(appId, expectedGeneration))
            if (changed != 1) throw IllegalStateException("Active data generation changed")
        }
    }

    fun setDeveloperEnabled(appId: String, enabled: Boolean) {
        writableDatabase.update("instances", ContentValues().apply {
            put("developer_enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    fun getGrant(appId: String, trustRevision: Long, capability: String, scope: String = ""): String? =
        readableDatabase.query("grants", arrayOf("decision"),
            "app_id = ? AND trust_revision = ? AND capability = ? AND resource_scope = ?",
            arrayOf(appId, trustRevision.toString(), capability, scope), null, null, null
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putGrant(appId: String, trustRevision: Long, capability: String, decision: String, scope: String = "") {
        val values = ContentValues().apply {
            put("app_id", appId); put("trust_revision", trustRevision); put("capability", capability)
            put("resource_scope", scope); put("decision", decision); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("grants", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun grantsJson(appId: String, trustRevision: Long): JSONObject {
        val items = JSONArray()
        readableDatabase.query("grants", arrayOf("capability", "resource_scope", "decision", "updated_at"),
            "app_id = ? AND trust_revision = ?", arrayOf(appId, trustRevision.toString()), null, null, "capability, resource_scope"
        ).use { c ->
            while (c.moveToNext()) items.put(JSONObject()
                .put("capability", c.getString(0)).put("scope", c.getString(1))
                .put("decision", c.getString(2)).put("updatedAt", c.getLong(3)))
        }
        return JSONObject().put("grants", items)
    }

    fun clearGrant(appId: String, trustRevision: Long, capability: String, scope: String = ""): Boolean =
        writableDatabase.delete("grants", "app_id = ? AND trust_revision = ? AND capability = ? AND resource_scope = ?",
            arrayOf(appId, trustRevision.toString(), capability, scope)) > 0

    fun observeSystemPermission(permission: String, granted: Boolean) {
        writableDatabase.insertWithOnConflict("system_permission_observations", null, ContentValues().apply {
            put("permission", permission); put("granted", if (granted) 1 else 0)
            put("observed_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun systemPermissionsJson(): JSONArray {
        val items = JSONArray()
        readableDatabase.query("system_permission_observations", null, null, null, null, null, "permission").use { cursor ->
            while (cursor.moveToNext()) items.put(JSONObject()
                .put("permission", cursor.string("permission"))
                .put("granted", cursor.int("granted") != 0)
                .put("observedAt", cursor.long("observed_at")))
        }
        return items
    }

    fun recordDiagnostic(appId: String?, sessionId: String, method: String, decision: String, resultCode: String?, durationMs: Long) {
        runCatching {
            writableDatabase.inTransaction {
                insert("diagnostic_events", null, ContentValues().apply {
                    put("app_id", appId); put("session_id", sessionId.take(80)); put("method", method.take(128))
                    put("decision", decision.take(32)); put("result_code", resultCode?.take(64))
                    put("duration_ms", durationMs.coerceAtLeast(0)); put("created_at", System.currentTimeMillis())
                })
                execSQL("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT 1000)")
            }
        }
    }

    fun diagnosticEventCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM diagnostic_events", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun createOperation(appId: String?, kind: String, idempotencyKey: String?, inputHash: String?, expected: String?): String {
        if (idempotencyKey != null) {
            val selection = if (appId == null) "app_id IS NULL AND kind = ? AND idempotency_key = ?" else "app_id = ? AND kind = ? AND idempotency_key = ?"
            val args = if (appId == null) arrayOf(kind, idempotencyKey) else arrayOf(appId, kind, idempotencyKey)
            readableDatabase.query("operations", arrayOf("operation_id", "input_hash"), selection,
                args, null, null, null
            ).use {
                if (it.moveToFirst()) {
                    if (it.getString(1) != inputHash) throw IllegalStateException("Idempotency key reused")
                    return it.getString(0)
                }
            }
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("operations", null, ContentValues().apply {
            put("operation_id", id); put("app_id", appId); put("kind", kind); put("state", "queued")
            put("idempotency_key", idempotencyKey); put("input_hash", inputHash); put("expected_release_id", expected)
            put("created_at", now); put("updated_at", now)
        })
        return id
    }

    fun updateOperation(id: String, state: String, resultReleaseId: String? = null, errorCode: String? = null, errorMessage: String? = null) {
        writableDatabase.update("operations", ContentValues().apply {
            put("state", state); put("result_release_id", resultReleaseId); put("error_code", errorCode)
            put("error_message", errorMessage?.take(400)); put("updated_at", System.currentTimeMillis())
        }, "operation_id = ?", arrayOf(id))
    }

    fun operationJson(id: String): org.json.JSONObject? =
        readableDatabase.query("operations", null, "operation_id = ?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) null else org.json.JSONObject()
                .put("operationId", c.string("operation_id")).put("appId", c.stringOrNull("app_id"))
                .put("kind", c.string("kind")).put("state", c.string("state"))
                .put("resultReleaseId", c.stringOrNull("result_release_id"))
                .put("errorCode", c.stringOrNull("error_code")).put("errorMessage", c.stringOrNull("error_message"))
                .put("createdAt", c.long("created_at")).put("updatedAt", c.long("updated_at"))
        }

    fun deleteInstance(appId: String): WebAppInstance? {
        val instance = getInstance(appId) ?: return null
        writableDatabase.inTransaction {
            execSQL("UPDATE instances SET state = 'deleting', updated_at = ? WHERE app_id = ?", arrayOf<Any>(System.currentTimeMillis(), appId))
            insertWithOnConflict("profile_cleanup", null, ContentValues().apply {
                put("profile_name", instance.webProfileName); put("app_id", appId); put("state", "pending")
                put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return instance
    }

    fun finishDelete(appId: String) {
        writableDatabase.delete("instances", "app_id = ? AND state = 'deleting'", arrayOf(appId))
    }

    fun pendingProfileCleanup(): List<Pair<String, String>> =
        readableDatabase.query("profile_cleanup", arrayOf("profile_name", "app_id"), "state = ?", arrayOf("pending"), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    fun completeProfileCleanup(profileName: String) {
        writableDatabase.delete("profile_cleanup", "profile_name = ?", arrayOf(profileName))
    }

    fun recoverInterruptedOperations() {
        writableDatabase.execSQL(
            "UPDATE operations SET state = 'failed', error_code = 'E_CANCELLED', error_message = 'Process restarted; retry with the same idempotency key', updated_at = ? WHERE state IN ('queued','transferring','validating','committing')",
            arrayOf(System.currentTimeMillis())
        )
    }

    private fun WebAppInstance.values() = ContentValues().apply {
        put("app_id", appId); put("name", name); put("mode", mode.name); put("start_url", startUrl)
        put("primary_origin", primaryOrigin); put("web_profile_name", webProfileName); put("trust_revision", trustRevision)
        put("active_release_id", activeReleaseId); put("active_data_generation", activeDataGeneration)
        put("source_adapter", sourceAdapter); put("source_spec", sourceSpec)
        put("developer_enabled", if (developerEnabled) 1 else 0); put("state", "ready")
        put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun CodeRelease.values() = ContentValues().apply {
        put("release_id", releaseId); put("app_id", appId); put("tree_hash", treeHash); put("provenance", provenance)
        put("version_code", versionCode); put("version_name", versionName); put("source_revision", sourceRevision)
        put("entry_path", entryPath)
        put("relative_root", relativeRoot); put("created_at", createdAt)
    }

    private fun Cursor.toInstance() = WebAppInstance(
        appId = string("app_id"), name = string("name"), mode = DeliveryMode.valueOf(string("mode")),
        startUrl = string("start_url"), primaryOrigin = string("primary_origin"), webProfileName = string("web_profile_name"),
        trustRevision = long("trust_revision"), activeReleaseId = stringOrNull("active_release_id"),
        activeDataGeneration = string("active_data_generation"), sourceAdapter = string("source_adapter"),
        sourceSpec = string("source_spec"), developerEnabled = int("developer_enabled") != 0,
        createdAt = long("created_at"), updatedAt = long("updated_at")
    )

    private fun Cursor.toRelease() = CodeRelease(
        releaseId = string("release_id"), appId = string("app_id"), treeHash = string("tree_hash"),
        provenance = string("provenance"), versionCode = longOrNull("version_code"),
        versionName = stringOrNull("version_name"), sourceRevision = stringOrNull("source_revision"),
        entryPath = string("entry_path"),
        relativeRoot = string("relative_root"), createdAt = long("created_at")
    )

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.stringOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try { return block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    private fun createSystemPermissionObservations(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_permission_observations (
              permission TEXT PRIMARY KEY,
              granted INTEGER NOT NULL CHECK(granted IN (0,1)),
              observed_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    companion object { private const val VERSION = 3 }
}
