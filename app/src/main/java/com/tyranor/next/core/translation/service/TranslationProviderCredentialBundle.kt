package com.tyranor.next.core.translation.service

import org.json.JSONException
import org.json.JSONObject

/** Provider-specific credential fields are encoded only for the encrypted translation secret store. */
data class TranslationProviderCredentials(
    val first: String = "",
    val second: String = "",
    val region: String = "",
) {
    override fun toString(): String = "TranslationProviderCredentials([redacted])"
}

object TranslationProviderCredentialBundle {
    fun encode(protocolId: TranslationProtocolId, credentials: TranslationProviderCredentials): String {
        if (protocolId.category != TranslationServiceCategory.MACHINE_TRANSLATION ||
            protocolId == TranslationProtocolId.BING_TRANSLATOR_WEB
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("protocol", protocolId.wireId)
            .put("first", credentials.first.trim())
            .put("second", credentials.second.trim())
            .put("region", credentials.region.trim())
            .toString()
    }

    fun decode(protocolId: TranslationProtocolId, secret: String?): TranslationProviderCredentials? {
        if (protocolId.category != TranslationServiceCategory.MACHINE_TRANSLATION) return null
        if (protocolId == TranslationProtocolId.BING_TRANSLATOR_WEB) return TranslationProviderCredentials()
        if (secret.isNullOrBlank() || secret.length > MAX_SECRET_LENGTH) return null
        return try {
            val value = JSONObject(secret)
            if (value.optInt("version") != VERSION || value.optString("protocol") != protocolId.wireId) return null
            val credentials = TranslationProviderCredentials(
                first = value.optString("first").trim(),
                second = value.optString("second").trim(),
                region = value.optString("region").trim(),
            )
            if (credentials.first.length > MAX_FIELD_LENGTH || credentials.second.length > MAX_FIELD_LENGTH ||
                credentials.region.length > MAX_REGION_LENGTH
            ) {
                null
            } else {
                credentials
            }
        } catch (_: JSONException) {
            null
        }
    }

    fun isComplete(protocolId: TranslationProtocolId, secret: String?): Boolean {
        if (protocolId == TranslationProtocolId.BING_TRANSLATOR_WEB) return true
        val credentials = decode(protocolId, secret) ?: return false
        return when (protocolId) {
            TranslationProtocolId.NIUTRANS_TRANSLATION_V1,
            TranslationProtocolId.AZURE_TRANSLATOR_V3,
            TranslationProtocolId.DEEPL_TRANSLATE_V2 -> credentials.first.isNotBlank()
            TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1,
            TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1,
            TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 ->
                credentials.first.isNotBlank() && credentials.second.isNotBlank()
            else -> false
        }
    }

    private const val VERSION = 1
    private const val MAX_SECRET_LENGTH = 16_384
    private const val MAX_FIELD_LENGTH = 4_096
    private const val MAX_REGION_LENGTH = 64
}
