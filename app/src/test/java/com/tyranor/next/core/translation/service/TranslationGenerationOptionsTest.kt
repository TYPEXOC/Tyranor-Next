package com.tyranor.next.core.translation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TranslationGenerationOptionsTest {
    @Test
    fun encodesAndDecodesEachProtocolParameterWithoutSecrets() {
        val options = TranslationGenerationOptions(
            temperature = 0.4,
            topP = 0.85,
            maxOutputTokens = 700,
            chatTokenLimitField = ChatCompletionTokenLimitField.MAX_TOKENS,
            frequencyPenalty = 0.2,
            presencePenalty = -0.1,
            thinkingLevel = TranslationThinkingLevel.HIGH,
            geminiTopK = 32,
            structuredOutputMode = TranslationStructuredOutputMode.JSON_SCHEMA,
            echoAlignmentEnabled = true,
        )

        assertEquals(options, TranslationGenerationOptions.decode(options.encode()))
        assertFalse(options.encode().contains("apiKey"))
        assertFalse(options.encode().contains("secret"))
    }

    @Test
    fun validatesRangesAndProtocolSpecificFields() {
        assertTrue(TranslationGenerationOptions(temperature = 2.0, topP = 1.0).isValidFor(TranslationProtocolId.OPENAI_RESPONSES))
        assertFalse(TranslationGenerationOptions(temperature = 2.1).isValidFor(TranslationProtocolId.OPENAI_COMPATIBLE_CHAT))
        assertFalse(TranslationGenerationOptions(topP = -0.1).isValidFor(TranslationProtocolId.GEMINI_INTERACTIONS))
        assertFalse(TranslationGenerationOptions(geminiTopK = 10).isValidFor(TranslationProtocolId.OPENAI_COMPATIBLE_CHAT))
        assertFalse(TranslationGenerationOptions(frequencyPenalty = 0.1).isValidFor(TranslationProtocolId.GEMINI_INTERACTIONS))
        assertFalse(TranslationGenerationOptions(maxOutputTokens = 0).isValidFor(TranslationProtocolId.OPENAI_RESPONSES))
        assertFalse(TranslationGenerationOptions(echoAlignmentEnabled = true).isValidFor(TranslationProtocolId.BING_TRANSLATOR_WEB))
        assertTrue(TranslationGenerationOptions(temperature = 0.8).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(TranslationGenerationOptions(temperature = 1.1).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(TranslationGenerationOptions(temperature = 0.2, topP = 0.8).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertTrue(TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.HIGH).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(
            TranslationGenerationOptions(structuredOutputMode = TranslationStructuredOutputMode.JSON_OBJECT)
                .isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES),
        )
        assertTrue(
            TranslationGenerationOptions(structuredOutputMode = TranslationStructuredOutputMode.JSON_SCHEMA)
                .isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES),
        )
    }

    @Test
    fun preservesLegacyGeminiSchemaBehaviorWhenModeWasNotStored() {
        assertFalse(TranslationGenerationOptions.decode("{}").echoAlignmentEnabled)
        assertEquals(
            TranslationStructuredOutputMode.JSON_SCHEMA,
            TranslationGenerationOptions.decode("{}", TranslationProtocolId.GEMINI_INTERACTIONS).structuredOutputMode,
        )
        assertEquals(
            TranslationStructuredOutputMode.DISABLED,
            TranslationGenerationOptions.decode("{}", TranslationProtocolId.OPENAI_RESPONSES).structuredOutputMode,
        )
    }

    @Test
    fun appendsEchoProtocolOnlyWhenTheOptInIsEnabled() {
        val prompt = "Translate the text."
        assertEquals(prompt, TranslationGenerationOptions().applyEchoAlignmentInstruction(prompt))
        assertTrue(
            TranslationGenerationOptions(echoAlignmentEnabled = true)
                .applyEchoAlignmentInstruction(prompt)
                .contains("`src` field"),
        )
    }

    @Test
    fun migratesLegacyThinkingSettingsToUnifiedLevels() {
        assertEquals(
            TranslationThinkingLevel.MINIMAL,
            TranslationGenerationOptions.decode("""{"reasoningEffort":null,"geminiThinkingLevel":"MINIMAL"}""").thinkingLevel,
        )
        assertEquals(
            TranslationThinkingLevel.XHIGH,
            TranslationGenerationOptions.decode("""{"reasoningEffort":"XHIGH"}""").thinkingLevel,
        )
        assertEquals(
            TranslationThinkingLevel.NONE,
            TranslationGenerationOptions.decode("""{"reasoningEffort":"NONE"}""").thinkingLevel,
        )
    }

    @Test
    fun sendsEverySharedEffortLevelThroughOpenAiProtocols() {
        TranslationThinkingLevel.entries.forEach { level ->
            assertEquals(
                level.wireValue,
                TranslationGenerationOptions(thinkingLevel = level)
                    .addOpenAiChatParameters(JSONObject())
                    .getString("reasoning_effort"),
            )
            assertEquals(
                level.wireValue,
                TranslationGenerationOptions(thinkingLevel = level)
                    .addOpenAiResponsesParameters(JSONObject())
                    .getJSONObject("reasoning")
                    .getString("effort"),
            )
        }
        assertEquals(
            "minimal",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
                .addOpenAiResponsesParameters(JSONObject(), "gpt-6.2-astra")
                .getJSONObject("reasoning")
                .getString("effort"),
        )
        assertEquals(
            "low",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
                .addOpenAiChatParameters(JSONObject(), "gpt-6.1-sol")
                .getString("reasoning_effort"),
        )
    }

    @Test
    fun mapsGeminiEffortLevelsToTheModelSupportedSubset() {
        assertEquals(
            "low",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.MINIMAL)
                .geminiGenerationConfig("gemini-2.5-pro")
                .getString("thinking_level"),
        )
        assertEquals(
            "high",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.MEDIUM)
                .geminiGenerationConfig("gemini-3-pro-preview")
                .getString("thinking_level"),
        )
        assertEquals(
            "high",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.MAX)
                .geminiGenerationConfig("gemini-3.8-flash")
                .getString("thinking_level"),
        )
        assertEquals(
            "minimal",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.MINIMAL)
                .geminiGenerationConfig("gemini-3.1-flash-lite-image")
                .getString("thinking_level"),
        )
        assertEquals(
            "low",
            TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
                .geminiGenerationConfig("gemini-2.5-pro")
                .getString("thinking_level"),
        )
    }

    @Test
    fun mapsAnthropicLevelsToAvailableNativeEffortOrBudgets() {
        val oldModel = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.MAX)
            .addAnthropicParameters(JSONObject(), "claude-sonnet-4-5-20250929")
        assertEquals("enabled", oldModel.getJSONObject("thinking").getString("type"))
        assertEquals(3_072, oldModel.getJSONObject("thinking").getInt("budget_tokens"))
        assertTrue(oldModel.getInt("max_tokens") > oldModel.getJSONObject("thinking").getInt("budget_tokens"))

        val opus46 = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.XHIGH)
            .addAnthropicParameters(JSONObject(), "claude-opus-4-6")
        assertEquals("max", opus46.getJSONObject("output_config").getString("effort"))

        val opus48 = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.XHIGH)
            .addAnthropicParameters(JSONObject(), "claude-opus-4-8")
        assertEquals("xhigh", opus48.getJSONObject("output_config").getString("effort"))

        val sonnet46Off = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
            .addAnthropicParameters(JSONObject(), "claude-sonnet-4-6")
        assertEquals("disabled", sonnet46Off.getJSONObject("thinking").getString("type"))
        assertFalse(sonnet46Off.has("output_config"))

        val sonnet55Off = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
            .addAnthropicParameters(JSONObject(), "claude-sonnet-5-5")
        assertEquals("between_tools", sonnet55Off.getJSONObject("thinking").getString("type"))
        assertEquals("low", sonnet55Off.getJSONObject("output_config").getString("effort"))

        val opus55Off = TranslationGenerationOptions(thinkingLevel = TranslationThinkingLevel.NONE)
            .addAnthropicParameters(JSONObject(), "claude-opus-5-5")
        assertEquals("adaptive", opus55Off.getJSONObject("thinking").getString("type"))
        assertEquals("low", opus55Off.getJSONObject("output_config").getString("effort"))
    }
}
