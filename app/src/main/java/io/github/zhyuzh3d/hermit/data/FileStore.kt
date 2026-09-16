package io.github.zhyuzh3d.hermit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.zhyuzh3d.hermit.model.ErrorCodes
import io.github.zhyuzh3d.hermit.model.HermitException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class FileStore(private val context: Context) {
    data class StoredFile(val logicalId: String, val name: String, val mime: String, val size: Long, val sha256: String, val url: String)

    fun import(appId: String, generation: String, input: InputStream, name: String, mime: String): JSONObject {
        return importWithId(appId, generation, UUID.randomUUID().toString(), input, name, mime)
    }

    @Synchronized
    fun importWithId(appId: String, generation: String, logicalId: String, input: InputStream, name: String, mime: String): JSONObject {
        validateId(logicalId)
        val safeName = sanitizeName(name)
        val root = root(appId, generation)
        val temporary = File(root, ".incoming-$logicalId")
        val target = File(root, logicalId)
        if (target.exists() || metadata(appId, generation, logicalId) != null) {
            throw HermitException(ErrorCodes.CONFLICT, "文件 ID 已存在")
        }
        val (currentBytes, currentFiles) = usage(appId, generation)
        if (currentFiles >= MAX_APP_FILES) throw HermitException(ErrorCodes.QUOTA, "应用文件数量已达上限")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > MAX_FILE_BYTES) throw HermitException(ErrorCodes.QUOTA, "单个文件超过 64 MiB")
                    if (currentBytes + size > MAX_APP_FILE_BYTES) throw HermitException(ErrorCodes.QUOTA, "应用文件总量超过 256 MiB")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        val hash = digest.digest().hex()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw HermitException(ErrorCodes.STORAGE, "无法提交文件")
        }
        try {
            openIndex(appId, generation).use { db ->
                db.insertOrThrow("files", null, ContentValues().apply {
                    put("logical_id", logicalId); put("display_name", safeName); put("mime", normalizeMime(mime))
                    put("size", size); put("sha256", hash); put("object_url", objectUrl(logicalId)); put("created_at", System.currentTimeMillis())
                })
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return StoredFile(logicalId, safeName, normalizeMime(mime), size, hash, objectUrl(logicalId)).json()
    }

    fun writeText(appId: String, generation: String, name: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INLINE_BYTES) throw HermitException(ErrorCodes.QUOTA, "文本超过 256 KiB，请使用文件导入")
        return import(appId, generation, bytes.inputStream(), name, "text/plain")
    }

    fun readText(appId: String, generation: String, logicalId: String, maxBytes: Int = MAX_INLINE_BYTES): JSONObject {
        val metadata = metadata(appId, generation, logicalId) ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        if (!metadata.mime.startsWith("text/") && metadata.mime !in setOf("application/json", "application/xml")) {
            throw HermitException(ErrorCodes.INVALID_ARGUMENT, "该文件不是可内联读取的文本")
        }
        val boundedMax = maxBytes.coerceIn(1, MAX_EXTENDED_INLINE_BYTES)
        if (metadata.size > boundedMax) throw HermitException(ErrorCodes.QUOTA, "文件超过允许的内联读取上限")
        val text = file(appId, generation, logicalId).readText(Charsets.UTF_8)
        return metadata.json().put("text", text)
    }

    fun list(appId: String, generation: String): JSONObject {
        val array = JSONArray()
        openIndex(appId, generation).use { db ->
            db.query("files", null, null, null, null, null, "created_at ASC").use { c ->
                while (c.moveToNext()) array.put(StoredFile(
                    c.getString(c.getColumnIndexOrThrow("logical_id")),
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                ).json())
            }
        }
        return JSONObject().put("files", array)
    }

    @Synchronized
    fun delete(appId: String, generation: String, logicalId: String): JSONObject {
        validateId(logicalId)
        if (metadata(appId, generation, logicalId) == null) return JSONObject().put("deleted", false)
        val source = file(appId, generation, logicalId)
        val stagedDelete = File(source.parentFile, ".deleted-${UUID.randomUUID()}")
        if (!source.renameTo(stagedDelete)) throw HermitException(ErrorCodes.STORAGE, "无法准备删除文件")
        val removed = try {
            openIndex(appId, generation).use { db ->
                db.beginTransaction()
                try {
                    val rows = db.delete("files", "logical_id = ?", arrayOf(logicalId))
                    check(rows == 1) { "文件索引已经变化" }
                    db.setTransactionSuccessful()
                    true
                } finally { db.endTransaction() }
            }
        } catch (error: Throwable) {
            stagedDelete.renameTo(source)
            throw error
        }
        stagedDelete.delete()
        return JSONObject().put("deleted", removed)
    }

    fun metadata(appId: String, generation: String, logicalId: String): StoredFile? {
        validateId(logicalId)
        return openIndex(appId, generation).use { db ->
            db.query("files", null, "logical_id = ?", arrayOf(logicalId), null, null, null).use { c ->
                if (!c.moveToFirst()) null else StoredFile(
                    logicalId,
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                )
            }
        }
    }

    fun open(appId: String, generation: String, logicalId: String): FileInputStream {
        if (metadata(appId, generation, logicalId) == null) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        return FileInputStream(file(appId, generation, logicalId))
    }

    fun listStored(appId: String, generation: String): List<StoredFile> {
        val items = mutableListOf<StoredFile>()
        openIndex(appId, generation).use { db ->
            db.query("files", null, null, null, null, null, "logical_id").use { c ->
                while (c.moveToNext()) items += StoredFile(
                    c.getString(c.getColumnIndexOrThrow("logical_id")),
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                )
            }
        }
        return items
    }

    fun prepareShare(appId: String, generation: String, logicalId: String): Pair<Uri, StoredFile> {
        val metadata = metadata(appId, generation, logicalId)
            ?: throw HermitException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        val shareRoot = File(context.filesDir, "shared").apply { mkdirs() }
        shareRoot.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > SHARE_TTL_MS }
            ?.forEach { it.deleteRecursively() }
        val directory = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(directory, sanitizeName(metadata.name))
        FileInputStream(file(appId, generation, logicalId)).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target) to metadata
    }

    private fun openIndex(appId: String, generation: String): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(File(root(appId, generation), "files.sqlite"), null).also { db ->
            db.enableWriteAheadLogging()
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS files (
                  logical_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                  mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                  object_url TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
            """.trimIndent())
            val hasObjectUrl = db.rawQuery("PRAGMA table_info(files)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "object_url") found = true
                found
            }
            if (!hasObjectUrl) {
                // File bodies are intentionally not reconstructed through a
                // legacy index. This test-device cutover removes stale metadata
                // and starts with the single current object-storage contract.
                db.execSQL("DROP TABLE IF EXISTS files")
                db.execSQL("""
                    CREATE TABLE files (
                      logical_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                      mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                      object_url TEXT NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                root(appId, generation).listFiles()
                    ?.filter { it.isFile && it.name.matches(ID) }
                    ?.forEach(File::delete)
            }
        }

    private fun usage(appId: String, generation: String): Pair<Long, Long> = openIndex(appId, generation).use { db ->
        root(appId, generation).listFiles { file -> file.name.startsWith(".deleted-") }?.forEach { it.delete() }
        db.rawQuery("SELECT COALESCE(SUM(size), 0), COUNT(*) FROM files", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) to cursor.getLong(1)
        }
    }

    private fun root(appId: String, generation: String): File {
        require(appId.matches(ID))
        require(generation.matches(ID))
        return File(context.filesDir, "instances/$appId/data/$generation/files").apply { mkdirs() }
    }
    private fun file(appId: String, generation: String, id: String): File {
        validateId(id)
        return File(root(appId, generation), id)
    }
    private fun validateId(id: String) {
        if (!id.matches(ID)) throw HermitException(ErrorCodes.INVALID_ARGUMENT, "非法文件 ID")
    }
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\/\u0000-\u001F]"), "_").trim().take(120).ifBlank { "file" }
    private fun normalizeMime(mime: String): String =
        mime.lowercase().takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) } ?: "application/octet-stream"
    private fun StoredFile.json() = JSONObject().put("logicalFileId", logicalId).put("url", url).put("name", name)
        .put("mime", mime).put("size", size).put("sha256", sha256)
    private fun objectUrl(logicalId: String) = "/__hermit/files/$logicalId"
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{36}")
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_APP_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_APP_FILES = 10_000L
        private const val MAX_INLINE_BYTES = 256 * 1024
        private const val MAX_EXTENDED_INLINE_BYTES = 8 * 1024 * 1024
        private const val SHARE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
