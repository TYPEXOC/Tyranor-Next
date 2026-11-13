package com.tyranor.next.core.engine.translation

import android.content.Context
import android.net.Uri
import com.core.engine.TranslationMapContract
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

internal data class TranslationMapSessionSource(
    val ruleId: String,
    val file: File,
)

/** Persists opaque, per-launch source descriptors so a granted URI still works after process death. */
internal object TranslationMapSessions {
    private const val DIRECTORY_NAME = "translation_map_sessions"
    private const val SESSION_TTL_MS = 90L * 24 * 60 * 60 * 1000
    private val TOKEN_PATTERN = Regex("^[0-9a-fA-F-]{36}$")

    fun create(context: Context, ruleId: String, sourceFile: File): Uri? {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return null
        pruneExpired(directory)

        val token = UUID.randomUUID().toString()
        val target = File(directory, "$token.json")
        val temporary = File(directory, ".$token.tmp")
        val descriptor = JSONObject()
            .put("ruleId", ruleId)
            .put("sourcePath", sourceFile.absolutePath)
            .put("createdAt", System.currentTimeMillis())
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(descriptor.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            Uri.Builder()
                .scheme("content")
                .authority(context.packageName + TranslationMapContract.AUTHORITY_SUFFIX)
                .appendPath(TranslationMapContract.SESSION_PATH)
                .appendPath(token)
                .build()
        } catch (_: Exception) {
            temporary.delete()
            null
        }
    }

    fun read(context: Context, token: String): TranslationMapSessionSource? {
        if (!TOKEN_PATTERN.matches(token)) return null
        val record = File(directory(context), "$token.json")
        val descriptor = runCatching { JSONObject(record.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val ruleId = descriptor.optString("ruleId").takeIf(String::isNotBlank) ?: return null
        val sourcePath = descriptor.optString("sourcePath").takeIf(String::isNotBlank) ?: return null
        return TranslationMapSessionSource(ruleId = ruleId, file = File(sourcePath))
    }

    fun release(context: Context, token: String): Boolean {
        if (!TOKEN_PATTERN.matches(token)) return false
        return File(directory(context), "$token.json").delete()
    }

    private fun directory(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME)

    private fun pruneExpired(directory: File) {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .filter { it.lastModified() < cutoff }
            .forEach { it.delete() }
    }
}
