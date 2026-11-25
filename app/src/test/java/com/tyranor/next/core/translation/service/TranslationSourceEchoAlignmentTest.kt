package com.tyranor.next.core.translation.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSourceEchoAlignmentTest {
    @Test
    fun ignoresUnicodeWidthCasePunctuationAndWhitespaceDifferences() {
        assertTrue(TranslationSourceEchoAlignment.matches("ＡＢＣ DEF!", "abc\n def"))
        assertTrue(TranslationSourceEchoAlignment.matches("こんにちは、世界。", "こんにちは 世界"))
    }

    @Test
    fun acceptsEchoesAtOrAboveSimilarityThresholdAndRejectsLowerOnes() {
        assertTrue(TranslationSourceEchoAlignment.matches("abcdefghij", "abcdefghiX"))
        assertTrue(TranslationSourceEchoAlignment.matches("abcdefghij", "abcdefgXYj"))
        assertFalse(TranslationSourceEchoAlignment.matches("abcdefghij", "abcdefgXYZ"))
    }

    @Test
    fun rejectsMissingEchoAndEmptyNormalizedText() {
        assertFalse(TranslationSourceEchoAlignment.matches("hello", ""))
        assertFalse(TranslationSourceEchoAlignment.matches("!!!", "???"))
    }
}
