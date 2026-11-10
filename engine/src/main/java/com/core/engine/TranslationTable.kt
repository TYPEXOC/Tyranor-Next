package com.core.engine

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Web translation runtime constants and the shared App-private translation directory layout.
 * Candidate discovery and source-format parsing belong to the App `TranslationCatalog`; Web
 * hosts consume the normalized map through [TranslationSession].
 */
object TranslationTable {

    /** 翻译运行时 JS（engine assets 根；幂等，可重复注入）。 */
    const val RUNTIME_ASSET = "__translation_runtime.js"

    /** 译表惯例文件名（PC 端常见导出工具的默认产出名；自动识别时不作硬性要求）。 */
    const val TRANSLATION_FILE_NAME = "ManualTransFile.json"

    /**
     * App 私有译表路径：目录名取 gameId 的 SHA-256 十六进制，避免 uri 形式的
     * gameId 直接落盘为非法/超长目录名。
     *
     * @param fileName 目录内译表文件名；null 时返回目录内惯例名路径（供写入方参考）。
     */
    fun privateTranslationFile(context: Context, gameId: String, fileName: String?): File? {
        if (gameId.isBlank()) return null
        val external = context.getExternalFilesDir(null) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(File(File(external, "translations"), key), fileName?.takeIf(String::isNotBlank) ?: TRANSLATION_FILE_NAME)
    }

    /** App 私有译表目录（可放置多个译文 JSON，由主 App 翻译目录列出）。 */
    fun privateTranslationDir(context: Context, gameId: String): File? =
        privateTranslationFile(context, gameId, null)?.parentFile

    /** Resolve a selected candidate ID to its source file; discovery and parsing stay in the App. */
    fun resolveTranslationFile(
        context: Context,
        gameId: String,
        gameDir: String?,
        selectedCandidateId: String?,
    ): File? {
        if (selectedCandidateId.isNullOrBlank()) return null
        privateTranslationFile(context, gameId, selectedCandidateId)
            ?.takeIf { it.isFile }?.let { return it }
        if (!gameDir.isNullOrBlank()) {
            File(gameDir, selectedCandidateId).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /** 组装受守卫的灌表表达式：运行时未装好时静默跳过（onPageFinished 会再推一次）。 */
    fun buildSetterJs(mapText: String): String =
        "(function(){if(!window.__TRS_SET_TRANSLATIONS__)return null;" +
            "try{return window.__TRS_SET_TRANSLATIONS__(" + mapText + ");}" +
            "catch(e){return {error:String(e)};}})()"
}
