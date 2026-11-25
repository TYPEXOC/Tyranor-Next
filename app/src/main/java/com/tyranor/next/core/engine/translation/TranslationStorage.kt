package com.tyranor.next.core.engine.translation

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** App-owned paths for imported translation files; engine hosts consume only granted map URIs. */
internal object TranslationStorage {
    const val DEFAULT_TRANSLATION_FILE_NAME = "ManualTransFile.json"

    /** Resolve the App-private per-game translation file or directory. */
    fun privateTranslationFile(context: Context, gameId: String, fileName: String? = null): File? {
        if (gameId.isBlank()) return null
        val external = context.getExternalFilesDir(null) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        val directory = File(File(external, "translations"), key)
        return File(directory, fileName?.takeIf(String::isNotBlank) ?: DEFAULT_TRANSLATION_FILE_NAME)
    }

    fun privateTranslationDir(context: Context, gameId: String): File? =
        privateTranslationFile(context, gameId)?.parentFile

    /** Resolve a selected candidate in App storage first, then in the game root. */
    fun resolveTranslationFile(
        context: Context,
        gameId: String,
        gameDir: String?,
        selectedCandidateId: String?,
    ): File? {
        if (selectedCandidateId.isNullOrBlank()) return null
        privateTranslationFile(context, gameId, selectedCandidateId)
            ?.takeIf { it.isFile }?.let { return it }
        if (!gameDir.isNullOrBlank()) {
            File(gameDir, selectedCandidateId).takeIf { it.isFile }?.let { return it }
        }
        return null
    }
}
