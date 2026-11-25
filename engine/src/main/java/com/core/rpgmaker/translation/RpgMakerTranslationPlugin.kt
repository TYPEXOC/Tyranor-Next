package com.core.rpgmaker.translation

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

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
    private data class HtmlInspection(val scripts: List<ScriptTag>, val baseHref: String?)

    private val HTML_TAG_START_REGEX = Regex("""<(/?)([a-z][a-z0-9:-]*)\b""", RegexOption.IGNORE_CASE)
    private val JAVASCRIPT_MIME_TYPES = setOf(
        "application/ecmascript",
        "application/javascript",
        "application/x-ecmascript",
        "application/x-javascript",
        "text/ecmascript",
        "text/javascript",
        "text/javascript1.0",
        "text/javascript1.1",
        "text/javascript1.2",
        "text/javascript1.3",
        "text/javascript1.4",
        "text/javascript1.5",
        "text/jscript",
        "text/livescript",
        "text/x-ecmascript",
        "text/x-javascript",
    )
    private val RAW_TEXT_ELEMENTS = setOf(
        "script", "style", "textarea", "title", "noscript", "xmp", "iframe", "noembed", "noframes", "plaintext",
    )

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
        val document = inspectHtml(html)
        if (document.baseHref != null && !isRootPreservingBaseHref(document.baseHref)) {
            return BootstrapInspection(false, "base href changes relative script paths")
        }
        val scripts = document.scripts
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

    /**
     * Scans actual HTML tags while skipping comments, raw-text element bodies, and inert templates.
     * A regex over the whole document also sees strings such as `'<script src="js/plugins.js">'`
     * inside inline JavaScript and can incorrectly conclude that the browser loads that script.
     */
    private fun inspectHtml(html: String): HtmlInspection {
        val scripts = mutableListOf<ScriptTag>()
        var templateDepth = 0
        var cursor = 0
        var baseHref: String? = null
        var foundBaseHref = false

        while (cursor < html.length) {
            val tagStart = html.indexOf('<', cursor)
            if (tagStart < 0) break
            if (html.startsWith("<!--", tagStart)) {
                val commentEnd = html.indexOf("-->", tagStart + 4)
                cursor = if (commentEnd < 0) html.length else commentEnd + 3
                continue
            }
            val tagMatch = HTML_TAG_START_REGEX.find(html, tagStart)
                ?.takeIf { it.range.first == tagStart }
            if (tagMatch == null) {
                cursor = tagStart + 1
                continue
            }

            val tagEnd = findTagEnd(html, tagStart)
            if (tagEnd < 0) break
            val tagName = tagMatch.groupValues[2].lowercase(Locale.ROOT)
            val isClosing = tagMatch.groupValues[1].isNotEmpty()
            val tag = html.substring(tagStart, tagEnd + 1)

            if (tagName == "template") {
                if (isClosing) templateDepth = (templateDepth - 1).coerceAtLeast(0)
                else templateDepth++
            } else if (tagName == "script" && !isClosing && templateDepth == 0) {
                val attributes = parseAttributes(tag, tagMatch.range.last + 1)
                val type = attributes["type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                val isModule = type == "module"
                val isJavaScript = type.isNullOrEmpty() || type in JAVASCRIPT_MIME_TYPES
                if (isJavaScript || isModule) {
                    val src = attributes["src"]
                    scripts += ScriptTag(
                        start = tagStart,
                        src = src,
                        path = src?.let(::normalizeLocalScriptPath),
                        asyncOrDefer = "async" in attributes || "defer" in attributes || isModule,
                    )
                }
            } else if (tagName == "base" && !isClosing && templateDepth == 0 && !foundBaseHref) {
                val attributes = parseAttributes(tag, tagMatch.range.last + 1)
                if ("href" in attributes) {
                    baseHref = attributes.getValue("href")
                    foundBaseHref = true
                }
            }

            if (!isClosing && tagName in RAW_TEXT_ELEMENTS) {
                val rawEnd = if (tagName == "plaintext") -1 else findRawTextElementEnd(html, tagName, tagEnd + 1)
                cursor = if (rawEnd < 0) html.length else rawEnd
            } else {
                cursor = tagEnd + 1
            }
        }
        return HtmlInspection(scripts, baseHref)
    }

    /** Only accept base URLs that preserve the server-root paths used by the virtual resources. */
    private fun isRootPreservingBaseHref(href: String): Boolean =
        href.trim() in setOf("", "/", "./")

    /** Returns the first `>` outside quoted attribute values, or -1 for a malformed tag. */
    private fun findTagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        for (index in start + 1 until html.length) {
            val character = html[index]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return index
                }
            }
        }
        return -1
    }

    /** Parses attribute names and optional quoted/unquoted values from an opening tag. */
    private fun parseAttributes(tag: String, start: Int): Map<String, String> {
        val attributes = linkedMapOf<String, String>()
        var cursor = start
        while (cursor < tag.length - 1) {
            while (cursor < tag.length && (tag[cursor].isWhitespace() || tag[cursor] == '/')) cursor++
            val nameStart = cursor
            while (cursor < tag.length && !tag[cursor].isWhitespace() && tag[cursor] != '=' &&
                tag[cursor] != '/' && tag[cursor] != '>'
            ) {
                cursor++
            }
            if (cursor == nameStart) break
            val name = tag.substring(nameStart, cursor).lowercase(Locale.ROOT)
            while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++

            var value = ""
            if (cursor < tag.length && tag[cursor] == '=') {
                cursor++
                while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
                if (cursor < tag.length && (tag[cursor] == '\'' || tag[cursor] == '"')) {
                    val quote = tag[cursor++]
                    val valueStart = cursor
                    while (cursor < tag.length && tag[cursor] != quote) cursor++
                    value = tag.substring(valueStart, cursor)
                    if (cursor < tag.length) cursor++
                } else {
                    val valueStart = cursor
                    while (cursor < tag.length && !tag[cursor].isWhitespace() && tag[cursor] != '>') cursor++
                    value = tag.substring(valueStart, cursor)
                }
            }
            if (!attributes.containsKey(name)) attributes[name] = value
        }
        return attributes
    }

    /** Skips a raw-text element body so tag-like text inside it is not treated as markup. */
    private fun findRawTextElementEnd(html: String, tagName: String, start: Int): Int {
        var searchFrom = start
        while (searchFrom < html.length) {
            val closeStart = html.indexOf("</$tagName", searchFrom, ignoreCase = true)
            if (closeStart < 0) return -1
            val delimiterIndex = closeStart + tagName.length + 2
            if (delimiterIndex < html.length &&
                (html[delimiterIndex].isWhitespace() || html[delimiterIndex] == '>' || html[delimiterIndex] == '/')
            ) {
                val closeEnd = findTagEnd(html, closeStart)
                return if (closeEnd < 0) -1 else closeEnd + 1
            }
            searchFrom = closeStart + 2
        }
        return -1
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
