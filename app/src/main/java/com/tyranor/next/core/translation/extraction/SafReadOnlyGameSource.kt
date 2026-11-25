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
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val tree = Uri.parse(treeUri)
    private val root = DocumentFile.fromTreeUri(appContext, tree)

    override suspend fun list(relativePath: String): List<GameDocumentInfo> {
        return try {
            val directory = resolve(relativePath, allowEmpty = true)
                ?: return emptyList()
            if (!directory.isDirectory) return emptyList()
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
            current = findChild(current, segment) ?: return null
        }
        return current
    }

    private fun findChild(directory: DocumentFile, name: String): DocumentFile? {
        if (!directory.isDirectory) return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(directory.uri),
        )
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
        )
        var documentUri: Uri? = null
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            var entryCount = 0
            while (cursor.moveToNext()) {
                entryCount++
                if (entryCount > MAX_DIRECTORY_ENTRIES) {
                    throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
                }
                if (documentUri == null && cursor.getString(nameColumn) == name) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                }
            }
        }
        return documentUri?.let { DocumentFile.fromSingleUri(appContext, it) }
    }

    private companion object {
        const val MAX_DIRECTORY_ENTRIES = 50_000
    }
}
