package com.tyranor.next.core.translation.extraction

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/** SAF adapter that exposes only descendants of the persisted, read-only tree grant. */
internal class SafReadOnlyGameSource(
    context: Context,
    treeUri: String,
) : ReadOnlyGameSource {
    private val resolver = context.applicationContext.contentResolver
    private val root = DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(treeUri))

    override suspend fun list(relativePath: String): List<GameDocumentInfo> {
        val directory = resolve(relativePath, allowEmpty = true)
            ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return try {
            directory.listFiles().mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                if (name.isBlank() || name.contains('/') || name.contains('\\')) return@mapNotNull null
                GameDocumentInfo(name, document.isDirectory)
            }
        } catch (failure: SecurityException) {
            throw TranslationExtractionException(ExtractionErrorCode.DIRECTORY_PERMISSION_LOST, failure)
        } catch (failure: FileNotFoundException) {
            throw TranslationExtractionException(ExtractionErrorCode.DIRECTORY_PERMISSION_LOST, failure)
        }
    }

    override suspend fun open(relativePath: String): java.io.InputStream? {
        return try {
            val file = resolve(relativePath) ?: return null
            if (!file.isFile) return null
            resolver.openInputStream(file.uri)
        } catch (failure: SecurityException) {
            throw TranslationExtractionException(ExtractionErrorCode.DIRECTORY_PERMISSION_LOST, failure)
        } catch (failure: FileNotFoundException) {
            throw TranslationExtractionException(ExtractionErrorCode.DIRECTORY_PERMISSION_LOST, failure)
        }
    }

    private fun resolve(relativePath: String, allowEmpty: Boolean = false): DocumentFile? {
        var current = root ?: throw TranslationExtractionException(ExtractionErrorCode.DIRECTORY_PERMISSION_LOST)
        for (segment in validateRelativePath(relativePath, allowEmpty)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }
}
