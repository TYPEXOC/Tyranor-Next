package com.core.rpgmaker.translation

import java.io.File
import java.nio.charset.StandardCharsets

/** Virtual PluginManager entry and APK-backed runtime resource for RPG Maker MV/MZ. */
internal object RpgMakerTranslationPlugin {
    const val PLUGIN_NAME = "TyranorNextTranslation"
    const val PLUGIN_LIST_PATH = "js/plugins.js"
    const val PLUGIN_SCRIPT_PATH = "js/plugins/TyranorNextTranslation.js"

    data class Preflight(
        val supported: Boolean,
        val reason: String,
        val virtualPluginList: Boolean = false,
        val injectPluginListScriptTag: Boolean = false,
    )

    private data class BootstrapInspection(
        val supported: Boolean,
        val reason: String,
        val pluginListTagStart: Int? = null,
        val mainScriptTagStart: Int? = null,
    )

    private data class ScriptTag(val start: Int, val src: String?, val path: String?, val asyncOrDefer: Boolean)

    private val SCRIPT_TAG_REGEX = Regex("""<script\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SRC_REGEX = Regex(
        """(?:^|\s)src\s*=\s*(?:([\"'])(.*?)\1|([^\s>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val ASYNC_OR_DEFER_REGEX = Regex("""(?:^|\s)(?:async|defer)(?:\s|=|>)""", RegexOption.IGNORE_CASE)
    private val MODULE_TYPE_REGEX = Regex("""(?:^|\s)type\s*=\s*([\"'])module\1""", RegexOption.IGNORE_CASE)

    /**
     * Plan the in-memory PluginManager bootstrap without touching game files.
     * A game-owned plugins.js is appended when present; otherwise the host serves a virtual one.
     */
    fun inspectPluginList(
        contentRoot: File,
        asarRootPrefix: String,
        hasAsar: ((String) -> Boolean)?,
        readAsar: ((String) -> ByteArray?)?,
        hasAsarIgnoringCase: ((String) -> Boolean)? = null,
    ): Preflight {
        val pluginScriptPaths = listOf(
            "${asarRootPrefix}js/plugins/$PLUGIN_NAME.js",
            "${asarRootPrefix}js/plugins/${PLUGIN_NAME.lowercase()}.js",
            PLUGIN_SCRIPT_PATH,
            "js/plugins/${PLUGIN_NAME.lowercase()}.js",
        ).distinct()
        val pluginScriptExists = if (hasAsar != null) {
            pluginScriptPaths.any { hasAsar(it) || hasAsarIgnoringCase?.invoke(it) == true }
        } else {
            findCaseInsensitiveFile(contentRoot, PLUGIN_SCRIPT_PATH) != null
        }
        if (pluginScriptExists) return Preflight(false, "plugin script path already exists in the game")

        val pluginListCandidates = listOf("${asarRootPrefix}$PLUGIN_LIST_PATH", PLUGIN_LIST_PATH).distinct()
        val pluginListPath = if (hasAsar != null) {
            pluginListCandidates.firstOrNull(hasAsar)
        } else {
            findCaseInsensitiveFile(contentRoot, PLUGIN_LIST_PATH)?.relativeTo(contentRoot.canonicalFile)
                ?.invariantSeparatorsPath
        }
        if (pluginListPath == null && hasAsar != null &&
            pluginListCandidates.any { hasAsarIgnoringCase?.invoke(it) == true }
        ) {
            return Preflight(false, "game plugin list path uses non-standard casing")
        }
        val pluginListBytes = when {
            pluginListPath == null -> null
            hasAsar != null -> readAsar?.invoke(pluginListPath)
            else -> findCaseInsensitiveFile(contentRoot, pluginListPath)?.let { runCatching { it.readBytes() }.getOrNull() }
        }
        if (pluginListPath != null && pluginListBytes == null) {
            return Preflight(false, "game plugin list exists but cannot be read")
        }

        val entryHtml = readEntryHtml(contentRoot, asarRootPrefix, readAsar)
            ?: return Preflight(false, "standard entry page is missing or unreadable")
        val bootstrap = inspectBootstrap(entryHtml.toString(StandardCharsets.UTF_8))
        if (!bootstrap.supported) return Preflight(false, bootstrap.reason)

        if (pluginListBytes != null && containsPluginName(pluginListBytes.toString(StandardCharsets.UTF_8))) {
            return Preflight(false, "plugin name collision in game plugin list")
        }

        return Preflight(
            supported = true,
            reason = if (pluginListPath == null) "virtual plugin list will be served" else "game plugin list will be preserved and extended",
            virtualPluginList = pluginListPath == null,
            injectPluginListScriptTag = bootstrap.pluginListTagStart == null,
        )
    }

    /** Appended to a game-owned plugin list in the in-memory HTTP response. */
    fun buildRegistrationAppend(): ByteArray = ("\n" + registrationSource()).toByteArray(StandardCharsets.UTF_8)

    /** Served only when the game has no plugins.js file. */
    fun buildVirtualPluginList(): ByteArray = registrationSource().toByteArray(StandardCharsets.UTF_8)

    fun runtimeResource(assetBytes: ByteArray): Map<String, ByteArray> = mapOf(PLUGIN_SCRIPT_PATH to assetBytes)

    /** Insert a local plugin-list script immediately before a recognized standard main.js tag. */
    fun insertPluginListScriptBeforeMain(html: String): String? {
        val inspection = inspectBootstrap(html)
        if (!inspection.supported) return null
        if (inspection.pluginListTagStart != null) return html
        val mainStart = inspection.mainScriptTagStart ?: return null
        return html.substring(0, mainStart) +
            "<script type=\"text/javascript\" src=\"$PLUGIN_LIST_PATH\"></script>\n" +
            html.substring(mainStart)
    }

    private fun registrationSource(): String = """
        ;(function(root){
          var plugins = root["${'$'}plugins"];
          if (typeof plugins === "undefined" || plugins === null) {
            plugins = [];
            root["${'$'}plugins"] = plugins;
          }
          if (!Array.isArray(plugins)) {
            if (root.console && root.console.warn) root.console.warn("[TYN Translation] plugin list is not an array");
            return;
          }
          for (var i = 0; i < plugins.length; i++) {
            if (plugins[i] && plugins[i].name === "$PLUGIN_NAME") {
              if (root.console && root.console.warn) root.console.warn("[TYN Translation] plugin name collision");
              return;
            }
          }
          plugins.push({
            name: "$PLUGIN_NAME",
            status: true,
            description: "TYN built-in translation runtime",
            parameters: {}
          });
          root.__TRS_BOOTSTRAP_MODE__ = "virtual-plugin";
          root.__TRS_PLUGIN_REGISTERED__ = true;
        })(window);
    """.trimIndent()

    private fun inspectBootstrap(html: String): BootstrapInspection {
        // Ignore commented-out script examples while keeping source offsets stable for insertion.
        val searchable = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(html) { " ".repeat(it.value.length) }
        val scripts = SCRIPT_TAG_REGEX.findAll(searchable).map { match ->
            val tag = match.value
            val srcMatch = SRC_REGEX.find(tag)
            val src = srcMatch?.let { it.groups[2]?.value ?: it.groups[3]?.value }
            ScriptTag(
                start = match.range.first,
                src = src,
                path = src?.let(::normalizeLocalScriptPath),
                asyncOrDefer = ASYNC_OR_DEFER_REGEX.containsMatchIn(tag) || MODULE_TYPE_REGEX.containsMatchIn(tag),
            )
        }.toList()
        if (scripts.any { looksLikePluginListPath(it.src) && !isStandardPath(it.path, PLUGIN_LIST_PATH) }) {
            return BootstrapInspection(false, "plugin list is referenced through a non-standard URL")
        }
        val pluginScripts = scripts.filter { isStandardPath(it.path, PLUGIN_LIST_PATH) }
        val mainScripts = scripts.filter { isStandardPath(it.path, "js/main.js") }

        if (mainScripts.size != 1) {
            return BootstrapInspection(false, "standard js/main.js entry script not found or ambiguous")
        }
        if (pluginScripts.size > 1) {
            return BootstrapInspection(false, "multiple js/plugins.js script tags found")
        }
        val main = mainScripts.single()
        if (main.asyncOrDefer) {
            return BootstrapInspection(false, "js/main.js uses async, defer, or module loading")
        }
        val plugin = pluginScripts.singleOrNull()
        if (plugin?.asyncOrDefer == true) {
            return BootstrapInspection(false, "js/plugins.js uses async, defer, or module loading")
        }
        if (plugin != null && plugin.start > main.start) {
            return BootstrapInspection(false, "js/plugins.js is loaded after js/main.js")
        }
        return BootstrapInspection(
            supported = true,
            reason = "standard RPG Maker script order recognized",
            pluginListTagStart = plugin?.start,
            mainScriptTagStart = main.start,
        )
    }

    private fun normalizeLocalScriptPath(src: String): String? {
        val value = src.trim().substringBefore('#').substringBefore('?')
            .replace('\\', '/')
        if (value.isEmpty() || value.startsWith("//") || Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            return null
        }
        val segments = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/").lowercase()
    }

    private fun isStandardPath(actual: String?, expected: String): Boolean =
        actual == expected.lowercase()

    private fun containsPluginName(source: String): Boolean {
        val matcher = Regex(
            "(?:[\"']name[\"']|\\bname\\b)\\s*:\\s*[\"']${Regex.escape(PLUGIN_NAME)}[\"']",
            RegexOption.IGNORE_CASE,
        )
        return matcher.containsMatchIn(source)
    }

    private fun looksLikePluginListPath(src: String?): Boolean {
        if (src.isNullOrBlank()) return false
        val path = src.trim().substringBefore('#').substringBefore('?').replace('\\', '/')
        return path.substringAfterLast('/').equals("plugins.js", ignoreCase = true)
    }

    private fun readEntryHtml(
        contentRoot: File,
        asarRootPrefix: String,
        readAsar: ((String) -> ByteArray?)?,
    ): ByteArray? {
        if (readAsar != null) {
            val candidates = listOf(
                "${asarRootPrefix}index.html",
                "index.html",
                "www/index.html",
                "app/index.html",
                "resources/app/index.html",
            ).distinct()
            return candidates.firstNotNullOfOrNull(readAsar)
        }
        val index = findCaseInsensitiveFile(contentRoot, "index.html") ?: return null
        return runCatching { index.readBytes() }.getOrNull()
    }

    private fun findCaseInsensitiveFile(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        var current = canonicalRoot
        for (segment in relativePath.replace('\\', '/').split('/').filter(String::isNotEmpty)) {
            val exact = File(current, segment)
            if (exact.exists()) {
                current = exact
                continue
            }
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        val target = runCatching { current.canonicalFile }.getOrNull() ?: return null
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) return null
        return target.takeIf(File::isFile)
    }
}
