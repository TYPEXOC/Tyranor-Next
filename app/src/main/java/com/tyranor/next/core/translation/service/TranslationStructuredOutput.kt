package com.tyranor.next.core.translation.service

import org.json.JSONArray
import org.json.JSONObject

enum class TranslationStructuredOutputMode {
    DISABLED,
    JSON_OBJECT,
    JSON_SCHEMA,
}

internal fun TranslationProtocolId.supportedStructuredOutputModes(): List<TranslationStructuredOutputMode> = when (this) {
    TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
    TranslationProtocolId.OPENAI_RESPONSES,
    TranslationProtocolId.GEMINI_INTERACTIONS -> TranslationStructuredOutputMode.entries
    TranslationProtocolId.ANTHROPIC_MESSAGES -> listOf(
        TranslationStructuredOutputMode.DISABLED,
        TranslationStructuredOutputMode.JSON_SCHEMA,
    )
    else -> listOf(TranslationStructuredOutputMode.DISABLED)
}

internal fun translationResultSchema(proofreading: Boolean): JSONObject {
    val resultProperties = JSONObject()
        .put("id", JSONObject().put("type", "string"))
        .put(
            if (proofreading) "suggestedTranslation" else "translation",
            JSONObject().put("type", "string"),
        )
    val required = JSONArray().put("id").put(if (proofreading) "suggestedTranslation" else "translation")
    if (proofreading) {
        resultProperties.put("issues", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        required.put("issues")
    }
    val result = JSONObject()
        .put("type", "object")
        .put("properties", resultProperties)
        .put("required", required)
        .put("additionalProperties", false)
    return JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put("results", JSONObject().put("type", "array").put("items", result)),
        )
        .put("required", JSONArray().put("results"))
        .put("additionalProperties", false)
}

internal fun TranslationStructuredOutputMode.addOpenAiChatFormat(
    body: JSONObject,
    schema: JSONObject,
    name: String,
): JSONObject = body.apply {
    when (this@addOpenAiChatFormat) {
        TranslationStructuredOutputMode.DISABLED -> Unit
        TranslationStructuredOutputMode.JSON_OBJECT -> put("response_format", JSONObject().put("type", "json_object"))
        TranslationStructuredOutputMode.JSON_SCHEMA -> put(
            "response_format",
            JSONObject().put(
                "type",
                "json_schema",
            ).put("json_schema", JSONObject().put("name", name).put("strict", true).put("schema", schema)),
        )
    }
}

internal fun TranslationStructuredOutputMode.addOpenAiResponsesFormat(
    body: JSONObject,
    schema: JSONObject,
    name: String,
): JSONObject = body.apply {
    when (this@addOpenAiResponsesFormat) {
        TranslationStructuredOutputMode.DISABLED -> Unit
        TranslationStructuredOutputMode.JSON_OBJECT -> put(
            "text",
            JSONObject().put("format", JSONObject().put("type", "json_object")),
        )
        TranslationStructuredOutputMode.JSON_SCHEMA -> put(
            "text",
            JSONObject().put(
                "format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("name", name)
                    .put("strict", true)
                    .put("schema", schema),
            ),
        )
    }
}

internal fun TranslationStructuredOutputMode.geminiResponseFormat(schema: JSONObject): JSONObject? = when (this) {
    TranslationStructuredOutputMode.DISABLED -> null
    TranslationStructuredOutputMode.JSON_OBJECT -> JSONObject()
        .put("type", "text")
        .put("mime_type", "application/json")
    TranslationStructuredOutputMode.JSON_SCHEMA -> JSONObject()
        .put("type", "text")
        .put("mime_type", "application/json")
        .put("schema", schema)
}

internal fun TranslationStructuredOutputMode.addAnthropicFormat(
    body: JSONObject,
    schema: JSONObject,
): JSONObject = body.apply {
    when (this@addAnthropicFormat) {
        TranslationStructuredOutputMode.DISABLED -> Unit
        TranslationStructuredOutputMode.JSON_OBJECT -> throw TranslationServiceException(
            TranslationServiceErrorCode.INVALID_CONFIGURATION,
        )
        TranslationStructuredOutputMode.JSON_SCHEMA -> put(
            "output_config",
            (optJSONObject("output_config") ?: JSONObject()).put(
                "format",
                JSONObject().put("type", "json_schema").put("schema", schema),
            ),
        )
    }
}
