package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Volcengine Ark's dedicated seed translation model, exposed as translation-only capability. */
internal class DoubaoTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1) {
    override val maxBatchSize = 1

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val credentials = requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        val input = inputs.single()
        val content = JSONObject()
            .put("type", "input_text")
            .put("text", input.sourceText)
            .put(
                "translation_options",
                JSONObject().apply {
                    if (!isAutoLanguage(sourceLanguage)) put("source_language", doubaoLanguage(sourceLanguage))
                    put("target_language", doubaoLanguage(targetLanguage))
                },
            )
        val body = JSONObject()
            .put("model", MODEL)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(content))))
            .toString()
        val response = transport.execute(profile,
            Request.Builder()
                .url("${profile.baseUrl}/responses")
                .header("Authorization", "Bearer ${credentials.first}")
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val root = parseJsonObject(response.body)
        val output = root.optJSONArray("output")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val translated = (0 until output.length()).asSequence()
            .mapNotNull { output.optJSONObject(it)?.takeIf { item -> item.optString("type") == "message" }?.optJSONArray("content") }
            .flatMap { contentArray -> (0 until contentArray.length()).asSequence().mapNotNull { contentArray.optJSONObject(it) } }
            .firstOrNull { it.optString("type") == "output_text" }
            ?.optString("text")
            ?.takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(inputs, listOf(translated))
    }

    private fun doubaoLanguage(value: String): String = when (language(value).lowercase()) {
        "zh-cn", "zh-hans" -> "zh"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> language(value)
    }

    companion object {
        private const val MODEL = "doubao-seed-translation-250915"
    }
}
