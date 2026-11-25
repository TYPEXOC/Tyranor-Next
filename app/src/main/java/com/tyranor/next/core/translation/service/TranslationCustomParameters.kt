package com.tyranor.next.core.translation.service

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class TranslationParameterType {
    STRING,
    INTEGER,
    FLOAT,
    BOOLEAN,
    OBJECT,
    ARRAY,
}

data class TranslationCustomParameter(
    val name: String,
    val type: TranslationParameterType,
    val value: String,
) {
    override fun toString(): String = "TranslationCustomParameter(name=$name, type=$type, value=[redacted])"
}

/** User-supplied values are serialized only for encrypted translation-secret storage. */
data class TranslationCustomParameters(
    val headers: List<TranslationCustomParameter> = emptyList(),
    val body: List<TranslationCustomParameter> = emptyList(),
) {
    val isEmpty: Boolean get() = headers.isEmpty() && body.isEmpty()

    fun isValidForAiRequest(): Boolean {
        if (headers.size > MAX_HEADERS || body.size > MAX_BODY_PARAMETERS) return false
        if (headers.any { parameter ->
                parameter.type != TranslationParameterType.STRING ||
                    !HEADER_NAME.matches(parameter.name) ||
                    parameter.name.lowercase() in RESERVED_HEADERS ||
                    parameter.value.any { it.code !in 0x20..0x7e } ||
                    parameter.value.length > MAX_VALUE_LENGTH
            }
        ) return false
        if (body.any { parameter ->
                !BODY_NAME.matches(parameter.name) ||
                    parameter.name.lowercase() in RESERVED_BODY_FIELDS ||
                    parameter.value.length > MAX_VALUE_LENGTH ||
                    !parameter.hasValidValue()
            }
        ) return false
        if (headers.map { it.name.lowercase() }.distinct().size != headers.size ||
            body.map { it.name.lowercase() }.distinct().size != body.size
        ) return false
        return runCatching { encode().toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES }.getOrDefault(false)
    }

    fun applyHeaders(builder: Request.Builder): Request.Builder = builder.apply {
        headers.forEach { header(it.name, it.value) }
    }

    fun applyBody(bodyObject: JSONObject): JSONObject = bodyObject.apply {
        body.forEach { parameter -> put(parameter.name, parameter.toJsonValue()) }
    }

    internal fun encode(): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("headers", encodeParameters(headers))
        .put("body", encodeParameters(body))
        .toString()

    override fun toString(): String = "TranslationCustomParameters(headers=${headers.size}, body=${body.size})"

    private fun encodeParameters(parameters: List<TranslationCustomParameter>) = JSONArray().apply {
        parameters.forEach { parameter ->
            put(
                JSONObject()
                    .put("name", parameter.name)
                    .put("type", parameter.type.name)
                    .put("value", parameter.value),
            )
        }
    }

    private fun TranslationCustomParameter.hasValidValue(): Boolean = runCatching {
        when (type) {
            TranslationParameterType.STRING -> Unit
            TranslationParameterType.INTEGER -> value.toLongOrNull() ?: return false
            TranslationParameterType.FLOAT -> value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
            TranslationParameterType.BOOLEAN -> if (value != "true" && value != "false") return false
            TranslationParameterType.OBJECT -> JSONObject(value)
            TranslationParameterType.ARRAY -> JSONArray(value)
        }
    }.isSuccess

    private fun TranslationCustomParameter.toJsonValue(): Any = when (type) {
        TranslationParameterType.STRING -> value
        TranslationParameterType.INTEGER -> value.toLong()
        TranslationParameterType.FLOAT -> value.toDouble()
        TranslationParameterType.BOOLEAN -> value.toBooleanStrict()
        TranslationParameterType.OBJECT -> JSONObject(value)
        TranslationParameterType.ARRAY -> JSONArray(value)
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_HEADERS = 32
        private const val MAX_BODY_PARAMETERS = 64
        private const val MAX_VALUE_LENGTH = 8_192
        private const val MAX_TOTAL_BYTES = 32 * 1_024
        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")
        private val BODY_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")
        private val RESERVED_HEADERS = setOf(
            "authorization",
            "anthropic-version",
            "content-length",
            "content-type",
            "connection",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "x-api-key",
            "x-goog-api-key",
        )
        private val RESERVED_BODY_FIELDS = setOf(
            "model",
            "messages",
            "input",
            "instructions",
            "system",
            "system_instruction",
            "store",
            "stream",
            "response_format",
            "text",
            "output_config",
        )

        fun decode(json: String): TranslationCustomParameters {
            val root = JSONObject(json)
            require(root.optInt("version") == FORMAT_VERSION)
            val result = TranslationCustomParameters(
                headers = root.optJSONArray("headers").toParameters(),
                body = root.optJSONArray("body").toParameters(),
            )
            require(result.isValidForAiRequest())
            return result
        }

        private fun JSONArray?.toParameters(): List<TranslationCustomParameter> {
            if (this == null) return emptyList()
            return (0 until length()).map { index ->
                val value = getJSONObject(index)
                TranslationCustomParameter(
                    name = value.getString("name"),
                    type = TranslationParameterType.valueOf(value.getString("type")),
                    value = value.getString("value"),
                )
            }
        }
    }
}
