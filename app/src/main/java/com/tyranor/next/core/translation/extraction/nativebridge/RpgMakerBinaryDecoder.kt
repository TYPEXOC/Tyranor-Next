package com.tyranor.next.core.translation.extraction.nativebridge

import com.tyranor.next.core.translation.extraction.ExtractionErrorCode
import com.tyranor.next.core.translation.extraction.TranslationExtractionException

internal enum class RpgMakerBinaryKind(val nativeId: String) {
    RGSS_DATA("RGSS_DATA"),
    RGSS_ARCHIVE("RGSS_ARCHIVE"),
    RM2K_DATABASE("RM2K_DATABASE"),
    RM2K_TREE("RM2K_TREE"),
    RM2K_MAP("RM2K_MAP"),
}

internal fun interface RpgMakerBinaryDataDecoder {
    fun decode(kind: RpgMakerBinaryKind, fileName: String, input: ByteArray): ByteArray
}

/** Inert binary decoder backed by the app's arm64 Rust parsing library. */
internal object NativeRpgMakerBinaryDataDecoder : RpgMakerBinaryDataDecoder {
    @Volatile
    private var loaded = false

    override fun decode(kind: RpgMakerBinaryKind, fileName: String, input: ByteArray): ByteArray {
        ensureLoaded()
        val result = decodeBinary(kind.nativeId, fileName, input)
            ?: throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
        when (result.toString(Charsets.UTF_8)) {
            "!CORRUPT_RESOURCE" -> throw TranslationExtractionException(ExtractionErrorCode.CORRUPT_RESOURCE)
            "!RESOURCE_TOO_LARGE" -> throw TranslationExtractionException(ExtractionErrorCode.RESOURCE_TOO_LARGE)
            "!UNSUPPORTED_FORMAT" -> throw TranslationExtractionException(ExtractionErrorCode.UNSUPPORTED_FORMAT)
        }
        return result
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("translation_data_decoder")
            } catch (failure: UnsatisfiedLinkError) {
                throw TranslationExtractionException(ExtractionErrorCode.EXTRACTOR_UNAVAILABLE, failure)
            }
            loaded = true
        }
    }

    private external fun decodeBinary(kind: String, fileName: String, input: ByteArray): ByteArray?
}
