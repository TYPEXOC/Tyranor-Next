package com.tyranor.next.core.engine.translation

import android.content.Context
import android.net.Uri
import com.core.engine.TranslationTable
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleCapabilities
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.PerGameSettingsStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** UI-safe description of a discovered translation file. It intentionally contains no [File]. */
data class TranslationCandidate(
    val id: String,
    val displayName: String,
    val formatId: String,
)

/** A format implementation owned by the TYN application. Parsers normalize input to a JSON map. */
internal interface TranslationFormat {
    val id: String

    fun isCandidate(file: File): Boolean

    fun readMapText(file: File): String?
}

/** A registered engine/module discovery policy. The host App executes these rules. */
internal interface TranslationDiscoveryRule {
    val id: String
    val formatId: String

    fun discover(context: Context, gameId: String, gameDirectory: String?): List<TranslationCandidate>

    fun resolve(context: Context, gameId: String, gameDirectory: String?, candidateId: String): File?
}

/** Central registry for supported source formats. New format parsers are added here. */
internal object TranslationFormatRegistry {
    const val MTOOL_JSON = "mtool-json"

    private const val MAX_SCAN_BYTES = 16L * 1024 * 1024

    private val formats: Map<String, TranslationFormat> = listOf(
        object : TranslationFormat {
            override val id = MTOOL_JSON

            override fun isCandidate(file: File): Boolean {
                return readMap(file)?.length()?.let { it > 0 } == true
            }

            override fun readMapText(file: File): String? = readMap(file)?.toString()

            /**
             * MTool tables are flat source-to-target string maps. Reject nested JSON structures so
             * ordinary manifests such as NW.js package.json are not mistaken for translation maps.
             * Extra flat string entries remain valid, so adding a translation does not change the
             * file's classification.
             */
            private fun readMap(file: File): JSONObject? {
                if (!file.isFile || file.length() !in 1L..MAX_SCAN_BYTES) return null
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
                val trimmed = text.trim().removePrefix("\uFEFF").trim()
                if (!trimmed.startsWith("{")) return null
                val sourceMap = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
                val normalized = JSONObject()
                for (source in sourceMap.keys()) {
                    val value = sourceMap.opt(source)
                    if (value is JSONObject || value is JSONArray) return null
                    val target = value as? String ?: continue
                    if (source.isNotEmpty() && target.isNotEmpty()) {
                        normalized.put(source, target)
                    }
                }
                return normalized.takeIf { it.length() > 0 }
            }
        },
    ).associateBy(TranslationFormat::id)

    fun get(id: String): TranslationFormat? = formats[id]
}

/**
 * Main-App translation catalog. Selection is stored in the per-game settings partition; discovery
 * and format rules are selected centrally from the game/module.
 */
object TranslationCatalog {
    const val MTOOL_JSON_RULE = "mtool-json"

    private val rules: Map<String, TranslationDiscoveryRule> = listOf(
        MToolJsonDiscoveryRule,
    ).associateBy(TranslationDiscoveryRule::id)

    fun supports(game: ScanGame, module: RuntimeModuleDescriptor? = null): Boolean =
        ruleIdFor(game, module) != null

    /**
     * Runtime modules must declare an explicit rule ID. Unknown or missing rules are unsupported.
     */
    fun ruleIdFor(game: ScanGame, module: RuntimeModuleDescriptor? = null): String? = when (game.engine) {
        EngineType.RPG_MV, EngineType.RPG_MZ -> MTOOL_JSON_RULE
        EngineType.RUNTIME_MODULE -> {
            if (module == null || !module.enabled || module.packageName != game.runtimeModulePackage ||
                RuntimeModuleCapabilities.TRANSLATION !in module.capabilities
            ) {
                null
            } else {
                module.translationRuleId
            }
        }
        else -> null
    }?.takeIf(rules::containsKey)

    fun availableCandidates(
        context: Context,
        game: ScanGame,
        gameDirectory: String?,
        module: RuntimeModuleDescriptor? = null,
    ): List<TranslationCandidate> {
        val rule = ruleIdFor(game, module)?.let(rules::get) ?: return emptyList()
        return rule.discover(context, game.uri, gameDirectory)
    }

    fun selectedCandidateId(context: Context, game: ScanGame): String? =
        PerGameSettingsStore.getStr(context, game.uri, PerGameSettingsStore.F_TRANSLATION_LANGUAGE)
            ?.takeIf(String::isNotBlank)

    fun selectCandidate(context: Context, game: ScanGame, candidateId: String?) {
        PerGameSettingsStore.setStr(
            context,
            game.uri,
            PerGameSettingsStore.F_TRANSLATION_LANGUAGE,
            candidateId?.takeIf(String::isNotBlank),
        )
    }

    /** Check the selected path directly so a temporary directory-listing failure does not clear it. */
    fun hasCandidate(
        context: Context,
        game: ScanGame,
        gameDirectory: String?,
        candidateId: String,
        module: RuntimeModuleDescriptor? = null,
    ): Boolean {
        val rule = ruleIdFor(game, module)?.let(rules::get) ?: return false
        return rule.resolve(context, game.uri, gameDirectory, candidateId)?.isFile == true
    }

    internal fun createSelectedMapUri(
        context: Context,
        game: ScanGame,
        gameDirectory: String?,
        module: RuntimeModuleDescriptor? = null,
    ): Uri? {
        val rule = ruleIdFor(game, module)?.let(rules::get) ?: return null
        val selected = selectedCandidateId(context, game) ?: return null
        val file = rule.resolve(context, game.uri, gameDirectory, selected) ?: return null
        if (readMapText(file, rule.id) == null) return null
        return TranslationMapSessions.create(context, rule.id, file)
    }

    internal fun readMapText(file: File, ruleId: String): String? {
        val rule = rules[ruleId] ?: return null
        return TranslationFormatRegistry.get(rule.formatId)?.readMapText(file)
    }

    private object MToolJsonDiscoveryRule : TranslationDiscoveryRule {
        override val id = MTOOL_JSON_RULE
        override val formatId = TranslationFormatRegistry.MTOOL_JSON

        override fun discover(context: Context, gameId: String, gameDirectory: String?): List<TranslationCandidate> {
            val format = TranslationFormatRegistry.get(formatId) ?: return emptyList()
            val files = mutableListOf<File>()
            val seenNames = mutableSetOf<String>()

            TranslationTable.privateTranslationDir(context, gameId)
                ?.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                ?.filter(format::isCandidate)
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { file ->
                    if (seenNames.add(file.name)) files += file
                }

            val root = gameDirectory?.takeIf(String::isNotBlank)?.let(::File)
                ?.takeIf { it.isDirectory }
            root?.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                ?.filter(format::isCandidate)
                ?.sortedWith(compareBy({ it.name != TranslationTable.TRANSLATION_FILE_NAME }, { it.name.lowercase() }))
                ?.forEach { file ->
                    if (seenNames.add(file.name)) files += file
                }

            return files.map { file ->
                TranslationCandidate(
                    id = file.name,
                    displayName = file.name.removeJsonSuffix(),
                    formatId = formatId,
                )
            }
        }

        override fun resolve(context: Context, gameId: String, gameDirectory: String?, candidateId: String): File? =
            TranslationTable.resolveTranslationFile(context, gameId, gameDirectory, candidateId)
    }
}

private fun String.removeJsonSuffix(): String =
    if (endsWith(".json", ignoreCase = true)) dropLast(".json".length) else this
