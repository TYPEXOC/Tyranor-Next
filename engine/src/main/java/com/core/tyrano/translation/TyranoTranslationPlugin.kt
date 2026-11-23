package com.core.tyrano.translation

import com.core.engine.WebTranslationBootstrapRequest
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Builds an APK-backed Tyrano plugin and a virtual copy of the startup scenario. */
internal object TyranoTranslationPlugin {
    const val PLUGIN_NAME = "TyranorNextTranslation"
    private const val PLUGIN_ROOT = "data/others/plugin/$PLUGIN_NAME"
    private const val INIT_PATH = "$PLUGIN_ROOT/init.ks"
    private const val RUNTIME_PATH = "$PLUGIN_ROOT/runtime.js"
    private const val SCENARIO_DIRECTORY = "data/scenario/"
    private const val SCENARIO_INPUT_ID = "first_scenario_file"
    private const val SCENARIO_PLUGIN_MARKER = "; TYRANOR_NEXT_TRANSLATION_PLUGIN"

    data class Preparation(
        val supported: Boolean,
        val reason: String,
        val resources: Map<String, ByteArray> = emptyMap(),
        val transformIndexHtml: ((String) -> String?)? = null,
    )

    private data class ScenarioSelection(
        val path: String,
        val fileName: String,
        val configured: Boolean,
        val remote: Boolean,
    )

    private val INPUT_TAG_REGEX = Regex(
        """<input\b[^>]*\bid\s*=\s*(?:(["'])$SCENARIO_INPUT_ID\1|$SCENARIO_INPUT_ID(?=\s|/?>))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val VALUE_REGEX = Regex(
        """(?:^|\s)value\s*=\s*(?:(["'])(.*?)\1|([^\s>]+))""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PLUGIN_TAG_REGEX = Regex(
        """(?im)(?:\[\s*plugin\b|@\s*plugin\b)[^\r\n]*?\bname\s*=\s*""" +
            "(?:\"${Regex.escape(PLUGIN_NAME)}\"|'${Regex.escape(PLUGIN_NAME)}'|" +
            "${Regex.escape(PLUGIN_NAME)}(?=\\s|\\]))",
    )

    fun prepare(request: WebTranslationBootstrapRequest, runtime: ByteArray): Preparation {
        if (resourceExists(request, INIT_PATH) || resourceExists(request, RUNTIME_PATH)) {
            return Preparation(false, "virtual plugin resource path already exists in the game")
        }

        val indexHtml = readFile(request, "index.html")
            ?: return Preparation(false, "index.html is missing or unreadable")
        val selection = selectStartupScenario(indexHtml.toString(StandardCharsets.UTF_8))
            ?: return Preparation(false, "first_scenario_file uses an unsupported path")
        val existingScenario = readFile(request, selection.path)
        if (existingScenario == null && resourceExists(request, selection.path)) {
            return Preparation(false, "startup scenario exists but cannot be read")
        }
        if (existingScenario == null && selection.remote) {
            return Preparation(false, "configured remote startup scenario is not present in the game package")
        }
        if (existingScenario != null &&
            PLUGIN_TAG_REGEX.containsMatchIn(maskScenarioComments(decodeForInspection(existingScenario)))
        ) {
            return Preparation(false, "startup scenario already references the reserved TYN plugin name")
        }
        val scenario = existingScenario?.let(::prependPluginTag)
            ?: pluginScenario().toByteArray(StandardCharsets.UTF_8)

        val resources = linkedMapOf(
            INIT_PATH to pluginInitScenario().toByteArray(StandardCharsets.UTF_8),
            RUNTIME_PATH to runtime,
            selection.path to scenario,
        )
        val reason = when {
            existingScenario == null -> "virtual startup scenario created at ${selection.path}"
            selection.configured -> "startup scenario copied in memory and prefixed with the TYN plugin"
            else -> "default first.ks copied in memory and prefixed with the TYN plugin"
        }
        val transformIndexHtml: ((String) -> String?)? = if (selection.configured) {
            { html -> rewriteStartupScenario(html, selection.fileName) }
        } else {
            null
        }
        return Preparation(true, reason, resources, transformIndexHtml)
    }

    private fun selectStartupScenario(html: String): ScenarioSelection? {
        val searchableHtml = maskHtmlComments(html)
        val inputTags = INPUT_TAG_REGEX.findAll(searchableHtml).toList()
        if (inputTags.size > 1) return null
        val inputTag = inputTags.singleOrNull()
        val configuredValue = inputTag?.range?.let { range ->
            html.substring(range.first, range.last + 1)
        }?.let { tag ->
            VALUE_REGEX.find(tag)?.let { match -> match.groups[2]?.value ?: match.groups[3]?.value }
        }?.trim()?.takeIf(String::isNotEmpty)
        val scenarioPath = normalizeScenarioPath(configuredValue ?: "first.ks") ?: return null
        val configuredUri = configuredValue?.let { runCatching { URI(it) }.getOrNull() }
        val scheme = configuredUri?.scheme
        val remote = scheme?.equals("http", ignoreCase = true) == true ||
            scheme?.equals("https", ignoreCase = true) == true || configuredValue?.startsWith("//") == true
        return ScenarioSelection(
            scenarioPath,
            scenarioPath.removePrefix(SCENARIO_DIRECTORY),
            inputTag != null,
            remote,
        )
    }

    private fun rewriteStartupScenario(html: String, fileName: String): String? {
        val inputTags = INPUT_TAG_REGEX.findAll(maskHtmlComments(html)).toList()
        if (inputTags.size != 1) return null
        val range = inputTags.single().range
        val tag = html.substring(range.first, range.last + 1)
        val htmlFileName = fileName.replace("&", "&amp;").replace("\"", "&quot;")
        val valueMatch = VALUE_REGEX.find(tag)
        val updatedTag = if (valueMatch != null) {
            val leadingWhitespace = valueMatch.value.takeWhile(Char::isWhitespace)
            val replacement = leadingWhitespace + "value=\"$htmlFileName\""
            tag.replaceRange(valueMatch.range, replacement)
        } else {
            val closingBracket = tag.lastIndexOf('>')
            if (closingBracket < 0) return null
            val insertAt = if (tag.getOrNull(closingBracket - 1) == '/') closingBracket - 1 else closingBracket
            tag.substring(0, insertAt) + " value=\"$htmlFileName\"" + tag.substring(insertAt)
        }
        return html.replaceRange(range.first, range.last + 1, updatedTag)
    }

    private fun maskHtmlComments(html: String): String = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        .replace(html) { match -> " ".repeat(match.value.length) }

    private fun maskScenarioComments(source: String): String {
        val withoutLineComments = Regex("(?m)^[\\t ]*;[^\\r\\n]*").replace(source) { match ->
            " ".repeat(match.value.length)
        }
        return Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(withoutLineComments) { match -> " ".repeat(match.value.length) }
    }

    private fun normalizeScenarioPath(value: String): String? {
        val cleanValue = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
            .substringBefore('#').substringBefore('?').replace('\\', '/')
        if (cleanValue.isEmpty()) return null

        val uri = runCatching { URI(cleanValue) }.getOrNull()
        val uriPath = uri?.rawPath?.takeIf(String::isNotBlank)
        val path = uri?.path?.takeIf(String::isNotBlank) ?: uriPath ?: cleanValue
        val segments = path.split('/').filter(String::isNotEmpty)
        val scenarioIndex = segments.indexOfFirst { it.equals("data", ignoreCase = true) }
            .takeIf { index -> index >= 0 && segments.getOrNull(index + 1).equals("scenario", ignoreCase = true) }

        val relativeScenario = if (scenarioIndex != null) {
            segments.drop(scenarioIndex + 2)
        } else {
            if (uri?.scheme != null || cleanValue.startsWith("//")) return null
            val normalized = segments.dropWhile { it == "." }
            if (normalized.firstOrNull()?.equals("data", ignoreCase = true) == true) return null
            normalized
        }
        if (relativeScenario.isEmpty() || relativeScenario.any { it == "." || it == ".." }) return null
        var filename = relativeScenario.joinToString("/")
        if (filename.substringAfterLast('/').substringAfterLast('.', "").isEmpty()) filename += ".ks"
        return SCENARIO_DIRECTORY + filename
    }

    private fun pluginInitScenario(): String = """
        $SCENARIO_PLUGIN_MARKER
        [iscript]
        window.__TRS_BOOTSTRAP_MODE__ = "virtual-plugin";
        [endscript]
        [loadjs storage="plugin/$PLUGIN_NAME/runtime.js"]
        [return]
    """.trimIndent()

    private fun pluginScenario(): String = "$SCENARIO_PLUGIN_MARKER\n[plugin name=\"$PLUGIN_NAME\"]\n"

    private fun prependPluginTag(source: ByteArray): ByteArray {
        val marker = "$SCENARIO_PLUGIN_MARKER\r\n[plugin name=\"$PLUGIN_NAME\"]\r\n"
        val bomSize: Int
        val charset: Charset
        when {
            source.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> {
                bomSize = 3
                charset = StandardCharsets.UTF_8
            }
            source.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> {
                bomSize = 2
                charset = StandardCharsets.UTF_16LE
            }
            source.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> {
                bomSize = 2
                charset = StandardCharsets.UTF_16BE
            }
            else -> {
                bomSize = 0
                // Both UTF-8 and common legacy Japanese encodings preserve these ASCII bytes.
                charset = StandardCharsets.US_ASCII
            }
        }
        val prefix = marker.toByteArray(charset)
        return ByteArray(source.size + prefix.size).also { output ->
            source.copyInto(output, destinationOffset = 0, startIndex = 0, endIndex = bomSize)
            prefix.copyInto(output, destinationOffset = bomSize)
            source.copyInto(output, destinationOffset = bomSize + prefix.size, startIndex = bomSize)
        }
    }

    private fun decodeForInspection(bytes: ByteArray): String = when {
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        else -> String(bytes, StandardCharsets.UTF_8)
    }

    private fun readFile(request: WebTranslationBootstrapRequest, relativePath: String): ByteArray? {
        if (request.readAsar != null) {
            val candidates = listOf("${request.asarRootPrefix}$relativePath", relativePath).distinct()
            return candidates.firstNotNullOfOrNull { candidate ->
                if (request.hasAsar?.invoke(candidate) == true) request.readAsar.invoke(candidate) else null
            }
        }
        val file = findCaseInsensitiveFile(request.contentRoot, relativePath) ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun resourceExists(request: WebTranslationBootstrapRequest, relativePath: String): Boolean {
        if (request.hasAsar != null) {
            val candidates = listOf("${request.asarRootPrefix}$relativePath", relativePath)
            return candidates.any { candidate ->
                request.hasAsar.invoke(candidate) || request.hasAsarIgnoringCase?.invoke(candidate) == true
            }
        }
        return findCaseInsensitiveFile(request.contentRoot, relativePath) != null
    }

    private fun findCaseInsensitiveFile(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        var current = canonicalRoot
        for (segment in relativePath.replace('\\', '/').split('/').filter(String::isNotEmpty)) {
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        val target = runCatching { current.canonicalFile }.getOrNull() ?: return null
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) return null
        return target.takeIf(File::isFile)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
