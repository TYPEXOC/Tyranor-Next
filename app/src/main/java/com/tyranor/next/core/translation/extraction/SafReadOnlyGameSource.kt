package com.tyranor.next.core.translation.extraction

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/** SAF adapter that exposes only descendants of the persisted, read-only tree grant. */
internal class SafReadOnlyGameSource(
    context: Context,
    treeUri: String,
) : ReadOnlyGameSource {
    private val resolver = context.applicationContext.contentResolver
    private val tree = Uri.parse(treeUri)
    private val root = DocumentFile.fromTreeUri(context.applicationContext, tree)

    override suspend fun list(relativePath: String): List<GameDocumentInfo> {
        val directory = resolve(relativePath, allowEmpty = true)
            ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(directory.uri),
            )
            val projection = arrayOf(
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
            )
            val documents = mutableListOf<GameDocumentInfo>()
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                var entryCount = 0
                while (cursor.moveToNext()) {
                    entryCount++
                    if (entryCount > MAX_DIRECTORY_ENTRIES) {
                        throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
                    }
                    val name = cursor.getString(nameColumn) ?: continue
                    if (name.isBlank() || name.contains('/') || name.contains('\\')) continue
                    documents += GameDocumentInfo(
                        name = name,
                        isDirectory = cursor.getString(mimeTypeColumn) == Document.MIME_TYPE_DIR,
                    )
                }
            }
            documents
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

    private companion object {
        const val MAX_DIRECTORY_ENTRIES = 50_000
    }
}
