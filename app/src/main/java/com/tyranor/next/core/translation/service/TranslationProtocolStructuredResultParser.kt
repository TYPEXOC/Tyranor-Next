package com.tyranor.next.core.translation.service

import org.json.JSONException
import org.json.JSONObject

/** Validates provider-independent structured results against the submitted stable entry IDs. */
internal object TranslationProtocolStructuredResultParser {
    fun parseTranslations(
        content: String,
        inputs: List<TranslationInput>,
        requireSourceEcho: Boolean = false,
    ): TranslationBatchResponse {
        val results = results(content)
        val requested = inputs.associateBy(TranslationInput::entryId)
        val returned = mutableMapOf<String, MutableList<TranslationCandidate>>()
        val unknown = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            val id = result.optString("id").trim()
            if (id.isBlank()) continue
            if (id !in requested) unknown += id
            val translation = result.optString("translation").takeIf { result.has("translation") }
            val sourceEcho = result.opt("src") as? String
            returned.getOrPut(id, ::mutableListOf).add(TranslationCandidate(sourceEcho, translation))
        }

        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        for (input in inputs) {
            val candidates = returned[input.entryId].orEmpty()
            when {
                candidates.isEmpty() -> failures[input.entryId] = TranslationItemFailureCode.MISSING_RESPONSE
                candidates.size > 1 -> failures[input.entryId] = TranslationItemFailureCode.DUPLICATE_RESPONSE
                candidates.single().translation.isNullOrBlank() -> failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                requireSourceEcho && !TranslationSourceEchoAlignment.matches(
                    input.sourceText,
                    candidates.single().sourceEcho.orEmpty(),
                ) -> failures[input.entryId] = TranslationItemFailureCode.SOURCE_ECHO_MISMATCH
                else -> {
                    val translated = candidates.single().translation!!
                    if (TranslationTextIntegrity.compare(input.sourceText, translated).isNotEmpty()) {
                        failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                    } else {
                        successes += TranslationOutput(input.entryId, translated)
                    }
                }
            }
        }
        return TranslationBatchResponse(successes, failures, unknown)
    }

    fun parseProofreadSuggestions(content: String, inputs: List<ProofreadInput>): ProofreadBatchResponse {
        val results = results(content)
        val requested = inputs.associateBy(ProofreadInput::entryId)
        val returned = mutableMapOf<String, MutableList<Pair<String?, Set<String>?>>>()
        val unknown = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            val id = result.optString("id").trim()
            if (id.isBlank()) continue
            if (id !in requested) unknown += id
            val suggestion = result.optString("suggestedTranslation").takeIf { result.has("suggestedTranslation") }
            val rawIssues = result.optJSONArray("issues")
            val issues = rawIssues?.let { array ->
                buildSet {
                    for (issueIndex in 0 until array.length()) {
                        val code = array.optString(issueIndex)
                        if (code !in PROOFREAD_ISSUE_CODES) return@let null
                        add(code)
                    }
                }
            }
            returned.getOrPut(id, ::mutableListOf).add(suggestion to issues)
        }

        val successes = mutableListOf<ProofreadOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        for (input in inputs) {
            val candidates = returned[input.entryId].orEmpty()
            when {
                candidates.isEmpty() -> failures[input.entryId] = TranslationItemFailureCode.MISSING_RESPONSE
                candidates.size > 1 -> failures[input.entryId] = TranslationItemFailureCode.DUPLICATE_RESPONSE
                candidates.single().first.isNullOrBlank() || candidates.single().second == null ->
                    failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                else -> {
                    val suggestion = candidates.single().first!!
                    if (TranslationTextIntegrity.compare(input.sourceText, suggestion).isNotEmpty()) {
                        failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                    } else {
                        successes += ProofreadOutput(input.entryId, suggestion, candidates.single().second!!)
                    }
                }
            }
        }
        return ProofreadBatchResponse(successes, failures, unknown)
    }

    private fun results(content: String) = try {
        val normalized = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        JSONObject(normalized).optJSONArray("results")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    } catch (failure: JSONException) {
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    private val PROOFREAD_ISSUE_CODES = setOf("GRAMMAR", "MEANING", "TERMINOLOGY", "CONSISTENCY", "TONE", "OTHER")

    private data class TranslationCandidate(
        val sourceEcho: String?,
        val translation: String?,
    )
}
