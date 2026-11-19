package com.tyranor.next.core.translation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            reasoningEffort = TranslationReasoningEffort.HIGH,
            geminiTopK = 32,
            geminiThinkingLevel = GeminiThinkingLevel.MINIMAL,
            structuredOutputMode = TranslationStructuredOutputMode.JSON_SCHEMA,
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
        assertTrue(TranslationGenerationOptions(temperature = 0.8).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(TranslationGenerationOptions(temperature = 1.1).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(TranslationGenerationOptions(temperature = 0.2, topP = 0.8).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
        assertFalse(TranslationGenerationOptions(reasoningEffort = TranslationReasoningEffort.HIGH).isValidFor(TranslationProtocolId.ANTHROPIC_MESSAGES))
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
        assertEquals(
            TranslationStructuredOutputMode.JSON_SCHEMA,
            TranslationGenerationOptions.decode("{}", TranslationProtocolId.GEMINI_INTERACTIONS).structuredOutputMode,
        )
        assertEquals(
            TranslationStructuredOutputMode.DISABLED,
            TranslationGenerationOptions.decode("{}", TranslationProtocolId.OPENAI_RESPONSES).structuredOutputMode,
        )
    }
}
