package com.tyranor.next.core.translation.service

import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCustomParametersTest {
    @Test
    fun roundTripsTypedParametersAndRedactsValuesFromStringFormatting() {
        val secret = "custom-token-must-not-leak"
        val parameters = TranslationCustomParameters(
            headers = listOf(
                TranslationCustomParameter("X-Tenant", TranslationParameterType.STRING, secret),
            ),
            body = listOf(
                TranslationCustomParameter("attempts", TranslationParameterType.INTEGER, "3"),
                TranslationCustomParameter("temperature", TranslationParameterType.FLOAT, "0.4"),
                TranslationCustomParameter("enabled", TranslationParameterType.BOOLEAN, "true"),
                TranslationCustomParameter("metadata", TranslationParameterType.OBJECT, "{\"mode\":\"fast\"}"),
                TranslationCustomParameter("generation_config", TranslationParameterType.OBJECT, "{\"temperature\":0.8,\"thinking_level\":\"low\"}"),
                TranslationCustomParameter("stops", TranslationParameterType.ARRAY, "[\"a\",\"b\"]"),
            ),
        )

        assertTrue(parameters.isValidForAiRequest())
        assertEquals(parameters, TranslationCustomParameters.decode(parameters.encode()))
        assertFalse(parameters.toString().contains(secret))
        assertFalse(parameters.headers.first().toString().contains(secret))

        val request = parameters.applyHeaders(Request.Builder().url("https://example.test")).build()
        assertEquals(secret, request.header("X-Tenant"))
        val body = parameters.applyBody(JSONObject())
        assertEquals(3, body.getInt("attempts"))
        assertEquals(0.4, body.getDouble("temperature"), 0.0001)
        assertTrue(body.getBoolean("enabled"))
        assertEquals("fast", body.getJSONObject("metadata").getString("mode"))
        assertEquals(0.8, body.getJSONObject("generation_config").getDouble("temperature"), 0.0001)
        assertEquals("low", body.getJSONObject("generation_config").getString("thinking_level"))
        assertEquals("b", body.getJSONArray("stops").getString(1))
    }

    @Test
    fun rejectsUnsafeHeadersReservedBodyFieldsDuplicateNamesAndInvalidTypedValues() {
        val invalidParameters = listOf(
            TranslationCustomParameters(headers = listOf(
                TranslationCustomParameter("Authorization", TranslationParameterType.STRING, "Bearer private"),
            )),
            TranslationCustomParameters(headers = listOf(
                TranslationCustomParameter("X-Trace", TranslationParameterType.STRING, "first\r\nInjected: yes"),
            )),
            TranslationCustomParameters(body = listOf(
                TranslationCustomParameter("messages", TranslationParameterType.ARRAY, "[]"),
            )),
            TranslationCustomParameters(body = listOf(
                TranslationCustomParameter("temperature", TranslationParameterType.INTEGER, "0.2"),
            )),
            TranslationCustomParameters(body = listOf(
                TranslationCustomParameter("mode", TranslationParameterType.STRING, "one"),
                TranslationCustomParameter("MODE", TranslationParameterType.STRING, "two"),
            )),
        )

        invalidParameters.forEach { assertFalse(it.isValidForAiRequest()) }
    }
}
