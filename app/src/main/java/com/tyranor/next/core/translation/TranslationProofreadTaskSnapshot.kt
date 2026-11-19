package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import org.json.JSONArray
import org.json.JSONObject

/** Proofreading task configuration is immutable and contains only the encrypted-key reference. */
internal data class TranslationProofreadTaskSnapshot(
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
    val scope: TranslationProofreadScope,
    val glossaryId: String? = null,
    val glossaryName: String? = null,
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
        .put("glossaryId", glossaryId)
        .put("glossaryName", glossaryName)
        .put("scope", scope.name)
        .put("retryFailed", retryFailed)
        .put("glossary", JSONArray().apply {
            glossary.forEach { term ->
                put(JSONObject().put("source", term.sourceTerm).put("target", term.targetTerm).put("note", term.note))
            }
        })
        .toString()

    fun toServiceProfile(): TranslationServiceProfile {
        val glossaryText = glossary.joinToString("\n") { term ->
            "${term.sourceTerm} = ${term.targetTerm}" + (term.note?.let { " ($it)" } ?: "")
        }
        val completePrompt = if (glossaryText.isBlank()) prompt else "$prompt\n\nGlossary:\n$glossaryText"
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
            translationPrompt = "",
            secretRef = secretRef,
            updatedAt = 0,
            proofreadingPrompt = completePrompt,
        )
    }

    companion object {
        fun decode(json: String): TranslationProofreadTaskSnapshot {
            val value = JSONObject(json)
            val protocol = TranslationProtocolId.entries.firstOrNull { it.wireId == value.optString("protocolId") }
                ?: throw IllegalArgumentException("Unsupported task protocol")
            val scope = runCatching { TranslationProofreadScope.valueOf(value.getString("scope")) }.getOrNull()
                ?: throw IllegalArgumentException("Unsupported proofreading scope")
            val terms = value.optJSONArray("glossary") ?: JSONArray()
            return TranslationProofreadTaskSnapshot(
                serviceId = value.getString("serviceId"),
                serviceRevision = value.getLong("serviceRevision"),
                protocolId = protocol,
                baseUrl = value.getString("baseUrl"),
                modelId = value.getString("modelId"),
                secretRef = value.optString("secretRef").takeIf(String::isNotBlank),
                sourceLanguage = value.getString("sourceLanguage"),
                targetLanguage = value.getString("targetLanguage"),
                prompt = value.getString("prompt"),
                glossaryRevision = value.optLong("glossaryRevision"),
                glossary = (0 until terms.length()).mapNotNull { index ->
                    val term = terms.optJSONObject(index) ?: return@mapNotNull null
                    TranslationGlossaryTerm(
                        sourceTerm = term.optString("source"),
                        targetTerm = term.optString("target"),
                        note = term.optString("note").takeIf(String::isNotBlank),
                    )
                },
                scope = scope,
                glossaryId = value.optString("glossaryId").takeIf(String::isNotBlank),
                glossaryName = value.optString("glossaryName").takeIf(String::isNotBlank),
                retryFailed = value.optBoolean("retryFailed"),
            )
        }
    }
}
