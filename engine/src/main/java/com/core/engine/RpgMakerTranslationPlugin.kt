package com.core.engine

import java.io.File
import java.nio.charset.StandardCharsets

/** Virtual PluginManager entry and APK-backed runtime resource for RPG Maker MV/MZ. */
internal object RpgMakerTranslationPlugin {
    const val PLUGIN_NAME = "TyranorNextTranslation"
    const val PLUGIN_LIST_PATH = "js/plugins.js"
    const val PLUGIN_SCRIPT_PATH = "js/plugins/TyranorNextTranslation.js"

    data class Preflight(val supported: Boolean, val reason: String)

    /** Check the game's standard plugin list without changing any file on disk. */
    fun inspectPluginList(
        contentRoot: File,
        asarRootPrefix: String,
        hasAsar: ((String) -> Boolean)?,
        readAsar: ((String) -> ByteArray?)?,
    ): Preflight {
        val pluginScriptPaths = listOf("${asarRootPrefix}js/plugins/$PLUGIN_NAME.js", PLUGIN_SCRIPT_PATH).distinct()
        val pluginScriptExists = if (hasAsar != null) {
            pluginScriptPaths.any(hasAsar)
        } else {
            File(contentRoot, PLUGIN_SCRIPT_PATH).isFile
        }
        if (pluginScriptExists) return Preflight(false, "plugin script path already exists in the game")

        val source = if (readAsar != null) {
            val candidates = listOf("${asarRootPrefix}js/plugins.js", PLUGIN_LIST_PATH).distinct()
            candidates.firstNotNullOfOrNull(readAsar)
        } else {
            File(contentRoot, PLUGIN_LIST_PATH).takeIf(File::isFile)?.let { file ->
                runCatching { file.readBytes() }.getOrNull()
            }
        } ?: return Preflight(false, "standard plugin list missing or unreadable")

        val text = source.toString(StandardCharsets.UTF_8)
        if (!text.contains("${'$'}plugins")) {
            return Preflight(false, "plugin list does not expose the standard ${'$'}plugins array")
        }
        if (text.contains(PLUGIN_NAME)) return Preflight(false, "plugin name collision")
        return Preflight(true, "standard plugin list available")
    }

    /** Appended to the in-memory response after the game's existing plugin array. */
    fun buildRegistrationAppend(): ByteArray = """
        ;(function(root){
          var plugins = root["${'$'}plugins"];
          if (!plugins || typeof plugins.push !== "function") {
            if (root.console && root.console.warn) root.console.warn("[TYN Translation] plugin list unavailable");
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
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    fun runtimeResource(assetBytes: ByteArray): Map<String, ByteArray> = mapOf(PLUGIN_SCRIPT_PATH to assetBytes)
}
