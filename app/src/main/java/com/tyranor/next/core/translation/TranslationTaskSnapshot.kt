package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, non-secret task configuration. API credentials are represented only by secretRef. */
internal data class TranslationTaskSnapshot(
    val serviceId: String,
    val serviceRevision: Long,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val modelId: String,
    val secretRef: String?,
    val sourceLanguage: String,
    val targetLanguage: String,
    val prompt: String,
    val glossaryRevision: Long,
    val glossary: List<TranslationGlossaryTerm>,
    val scope: String = SCOPE_UNTRANSLATED,
    val retryFailed: Boolean = false,
) {
    fun encode(): String = JSONObject()
        .put("serviceId", serviceId)
        .put("serviceRevision", serviceRevision)
        .put("protocolId", protocolId.wireId)
        .put("baseUrl", baseUrl)
        .put("modelId", modelId)
        .put("secretRef", secretRef)
        .put("sourceLanguage", sourceLanguage)
        .put("targetLanguage", targetLanguage)
        .put("prompt", prompt)
        .put("glossaryRevision", glossaryRevision)
        .put("scope", scope)
        .put("glossary", JSONArray().apply {
            glossary.forEach { term ->
                put(JSONObject().put("source", term.sourceTerm).put("target", term.targetTerm).put("note", term.note))
            }
        })
        .put("retryFailed", retryFailed)
        .toString()

    fun toServiceProfile(): TranslationServiceProfile {
        val glossaryPrompt = glossary.takeIf { it.isNotEmpty() }?.joinToString("\n") { term ->
            "${term.sourceTerm} = ${term.targetTerm}" + (term.note?.let { " (${it})" } ?: "")
        }.orEmpty()
        val completePrompt = if (glossaryPrompt.isEmpty()) prompt else "$prompt\n\nGlossary (use these translations consistently):\n$glossaryPrompt"
        return TranslationServiceProfile(
            id = serviceId,
            displayName = serviceId,
            protocolId = protocolId,
            baseUrl = baseUrl,
            modelId = modelId,
            enabled = true,
            configured = true,
            hasSecret = secretRef != null,
            revision = serviceRevision,
            translationPrompt = completePrompt,
            secretRef = secretRef,
            updatedAt = 0,
        )
    }

    companion object {
        fun decode(json: String): TranslationTaskSnapshot {
            val value = JSONObject(json)
            val protocolId = TranslationProtocolId.entries.firstOrNull { it.wireId == value.optString("protocolId") }
                ?: throw IllegalArgumentException("Unsupported task protocol")
            val terms = value.optJSONArray("glossary") ?: JSONArray()
            return TranslationTaskSnapshot(
                serviceId = value.getString("serviceId"),
                serviceRevision = value.getLong("serviceRevision"),
                protocolId = protocolId,
                baseUrl = value.getString("baseUrl"),
                modelId = value.getString("modelId"),
                secretRef = value.optString("secretRef").takeIf(String::isNotBlank),
                sourceLanguage = value.getString("sourceLanguage"),
                targetLanguage = value.getString("targetLanguage"),
                prompt = value.getString("prompt"),
                glossaryRevision = value.optLong("glossaryRevision"),
                glossary = (0 until terms.length()).mapNotNull { index ->
                    val item = terms.optJSONObject(index) ?: return@mapNotNull null
                    TranslationGlossaryTerm(
                        sourceTerm = item.optString("source"),
                        targetTerm = item.optString("target"),
                        note = item.optString("note").takeIf(String::isNotBlank),
                    )
                },
                scope = value.optString("scope", SCOPE_UNTRANSLATED),
                retryFailed = value.optBoolean("retryFailed"),
            )
        }

        const val SCOPE_UNTRANSLATED = "UNTRANSLATED"
    }
}
