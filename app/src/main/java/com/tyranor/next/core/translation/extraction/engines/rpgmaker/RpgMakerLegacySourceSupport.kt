package com.tyranor.next.core.translation.extraction.engines.rpgmaker

import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.GameDocumentInfo
import com.tyranor.next.core.translation.extraction.ReadOnlyGameSource
import com.tyranor.next.core.translation.extraction.TranslationExtractionException
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object RpgMakerLegacySourceSupport {
    private val rgssDataFiles = setOf(
        "actors", "animations", "armors", "classes", "commonevents", "enemies", "items",
        "mapinfos", "skills", "states", "system", "tilesets", "troops", "weapons",
    )

    suspend fun findRgssDataFiles(source: ReadOnlyGameSource): List<String>? {
        val root = source.list("")
        val dataDirectory = root.firstOrNull { it.isDirectory && it.name.equals("Data", ignoreCase = true) }
        if (dataDirectory != null) {
            val children = source.list(dataDirectory.name).filterNot(GameDocumentInfo::isDirectory)
            val systemCandidates = children.filter { it.name.substringBeforeLast('.', "").equals("System", ignoreCase = true) }
            if (systemCandidates.size > 1) unsupported()
            val system = systemCandidates.singleOrNull() ?: unsupported()
            val extension = system.name.substringAfterLast('.', "").lowercase()
            if (extension !in RGSS_DATA_EXTENSIONS) unsupported()
            val hasMixedGenerationResource = children.any { child ->
                val stem = child.name.substringBeforeLast('.', "").lowercase()
                val resourceExtension = child.name.substringAfterLast('.', "").lowercase()
                (stem in rgssDataFiles || MAP_FILE.matches(child.name)) &&
                    resourceExtension in RGSS_DATA_EXTENSIONS && resourceExtension != extension
            }
            if (hasMixedGenerationResource) unsupported()
            val supported = children.filter { child ->
                val stem = child.name.substringBeforeLast('.', "").lowercase()
                val fileExtension = child.name.substringAfterLast('.', "").lowercase()
                fileExtension == extension && (stem in rgssDataFiles || MAP_FILE.matches(child.name))
            }.map { "${dataDirectory.name}/${it.name}" }.sorted()
            if (supported.none { it.substringAfterLast('/').substringBeforeLast('.').equals("System", ignoreCase = true) }) {
                unsupported()
            }
            return supported
        }

        val archives = root.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in RGSS_ARCHIVE_EXTENSIONS }
        if (archives.size > 1) unsupported()
        val archive = archives.singleOrNull() ?: return null
        return listOf(archive.name)
    }

    suspend fun findRm2kFiles(source: ReadOnlyGameSource): List<String> {
        val root = source.list("").filterNot(GameDocumentInfo::isDirectory)
        val database = root.firstOrNull { it.name.equals("RPG_RT.ldb", ignoreCase = true) }
        val tree = root.firstOrNull { it.name.equals("RPG_RT.lmt", ignoreCase = true) }
        if (database == null || tree == null) unsupported()
        val maps = root.filter { MAP_LMU_FILE.matches(it.name) }
        return buildList {
            add(database.name)
            add(tree.name)
            addAll(maps.map(GameDocumentInfo::name).sorted())
        }
    }

    fun isRgssArchive(relativePath: String): Boolean =
        relativePath.substringAfterLast('/').substringAfterLast('.', "").lowercase() in RGSS_ARCHIVE_EXTENSIONS

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > limit) {
                throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
    }

    private fun unsupported(): Nothing =
        throw TranslationExtractionException(ExtractionErrorCode.UNSUPPORTED_FORMAT)

    private val MAP_FILE = Regex("(?i)Map\\d+\\.(rxdata|rvdata|rvdata2)")
    private val MAP_LMU_FILE = Regex("(?i)Map\\d+\\.lmu")
    private val RGSS_DATA_EXTENSIONS = setOf("rxdata", "rvdata", "rvdata2")
    private val RGSS_ARCHIVE_EXTENSIONS = setOf("rgssad", "rgss2a", "rgss3a")
}
