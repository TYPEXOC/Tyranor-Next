package com.core.engine

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ValueCallback
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

private const val TAG = "TranslationTable"

/**
 * MTool 导出格式译表注入（MV/MZ/Tyrano Web 宿主共享）。
 *
 * 译表来自 PC 端提取工具产出的 `ManualTransFile.json`（键=原文、值=译文），运行时
 * JS（`__translation_runtime.js`，engine assets）在页面内自装挂载点并暴露
 * `window.__TRS_SET_TRANSLATIONS__`；Kotlin 侧只负责「找到译表文件 → 原文灌入页面」
 * 与热更新监听，不做任何文案映射（AGENTS.md 错误协议：宿主不产出本地化文案）。
 *
 * 译表来源（[resolveTranslationFile]）：仅使用用户在游戏抽屉「翻译设置」中**主动选定**
 * 的文件——App 私有目录 `<externalFilesDir>/translations/<sha256(gameId)>/<选定文件>.json`
 * （可写、热更新友好，原游戏目录在 SAF 下只读）优先，其次游戏目录根同名文件（只读兜底）。
 * 默认（未选定或选定文件不存在）**不翻译、保持原文**；自动识别
 * （[gameRootTranslationFiles]）只用于列出候选，不参与默认解析。
 */
object TranslationTable {

    /** 翻译运行时 JS（engine assets 根；幂等，可重复注入）。 */
    const val RUNTIME_ASSET = "__translation_runtime.js"

    /** 译表惯例文件名（PC 端常见导出工具的默认产出名；自动识别时不作硬性要求）。 */
    const val TRANSLATION_FILE_NAME = "ManualTransFile.json"

    /** 自动识别扫描的单文件体积上限：超过则跳过（不读入内存做格式校验）。 */
    private const val MAX_SCAN_BYTES = 16L * 1024 * 1024

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

    /** App 私有译表目录（可放置多个译文 JSON，供「语言」选择）。 */
    fun privateTranslationDir(context: Context, gameId: String): File? =
        privateTranslationFile(context, gameId, null)?.parentFile

    /**
     * 列出该游戏可用的译文文件（「翻译设置」下拉数据源）：
     * App 私有目录下全部 *.json（按名称排序），外加游戏目录根的自动识别结果
     * （[gameRootTranslationFiles]，任意 MTool 导出格式 JSON，不限文件名）。
     * 同名时私有目录优先（与 [resolveTranslationFile] 一致）。
     */
    fun availableTranslationFiles(context: Context, gameId: String, gameDir: String?): List<File> {
        val files = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        privateTranslationDir(context, gameId)
            ?.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".json") }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { f ->
                if (seen.add(f.name)) files.add(f)
            }
        gameRootTranslationFiles(gameDir).forEach { f ->
            if (seen.add(f.name)) files.add(f)
        }
        return files
    }

    /**
     * 自动识别游戏目录根的翻译格式 JSON（不限 `ManualTransFile.json` 文件名）：
     * 仅扫描根目录一层 *.json，内容须为 MTool 导出格式（[looksLikeTranslationJson]），
     * 按文件名排序。惯例名排最前。结果仅作为「翻译设置」的候选列表，
     * 不参与默认解析（默认不翻译，用户主动选定才生效）。
     */
    fun gameRootTranslationFiles(gameDir: String?): List<File> {
        if (gameDir.isNullOrBlank()) return emptyList()
        val root = File(gameDir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".json") }
            ?.filter { looksLikeTranslationJson(it) }
            ?.sortedWith(compareBy({ it.name != TRANSLATION_FILE_NAME }, { it.name.lowercase() }))
            ?: emptyList()
    }

    /**
     * MTool 导出格式识别：JSON 顶层对象、值均为基本类型（字符串/数字/布尔/null，
     * 不含嵌套数组/对象——这是与游戏数据 JSON 的天然区分）、至少一条「字符串→字符串」
     * 条目。容忍 UTF-8 BOM（Windows 编辑器保存 UTF-8 常带 BOM）。用于在游戏根目录
     * 自动发现译文文件。
     */
    fun looksLikeTranslationJson(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len == 0L || len > MAX_SCAN_BYTES) return false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val obj = parseTranslationObject(text) ?: return false
        var entries = 0
        var stringPairs = 0
        for (key in obj.keys()) {
            when (val value = obj.opt(key)) {
                is JSONObject, is org.json.JSONArray -> return false
                is String -> if (value.isNotEmpty()) stringPairs++
            }
            entries++
        }
        return entries > 0 && stringPairs > 0
    }

    /** 去除 UTF-8 BOM 后解析顶层 JSON 对象；非法或非对象返回 null。 */
    private fun parseTranslationObject(text: String): JSONObject? {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    /**
     * 解析生效译表（带用户选择）：[selectedLanguage] 为用户在「翻译设置」中**主动选定**
     * 的文件名（私有目录/游戏目录根同名查找）。默认（未选定）或选定文件不存在时返回
     * null=不翻译、保持原文——自动识别只用于列出候选，不参与默认解析（无回退）。
     */
    fun resolveTranslationFile(
        context: Context,
        gameId: String,
        gameDir: String?,
        selectedLanguage: String?,
    ): File? {
        if (selectedLanguage.isNullOrBlank()) return null
        privateTranslationFile(context, gameId, selectedLanguage)
            ?.takeIf { it.isFile }?.let { return it }
        if (!gameDir.isNullOrBlank()) {
            File(gameDir, selectedLanguage).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * 读取并校验译表 JSON 文本。非法（非对象/不可解析）返回 null——译表原文直接拼进
     * 注入脚本，必须先确认是合法 JSON 对象，防止把任意文件内容灌进页面脚本。
     * 容忍 UTF-8 BOM：返回文本已剥离 BOM（BOM 拼进脚本会让页面 JSON.parse 失败）。
     */
    fun readMapText(file: File): String? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (!trimmed.startsWith("{")) return null
        runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return trimmed
    }

    /** 组装受守卫的灌表表达式：运行时未装好时静默跳过（onPageFinished 会再推一次）。 */
    fun buildSetterJs(mapText: String): String =
        "(function(){if(!window.__TRS_SET_TRANSLATIONS__)return null;" +
            "try{return window.__TRS_SET_TRANSLATIONS__(" + mapText + ");}" +
            "catch(e){return {error:String(e)};}})()"
}

/**
 * 单个 Web 宿主会话的译表注入控制器。
 *
 * 职责：加载运行时 JS 资产、后台读取译表、按页面生命周期注入、监听译表文件变化
 * 热更新。所有 [evaluateJs] 调用都从主线程发起（WebView 线程契约）；译表读取在
 * 后台线程完成，避免大 JSON 阻塞启动。
 *
 * @param evaluateJs 宿主提供的执行通道（注入脚本, 结果回调）；回调线程为主线程。
 */
class TranslationTableController(
    private val context: Context,
    private val gameId: String,
    private val gameDir: String?,
    /** 用户选择的译表文件名（「翻译设置」）；null/空 = 不翻译、保持原文。 */
    private val selectedLanguage: String? = null,
    private val evaluateJs: (String, ValueCallback<String>?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null

    /** 当前生效译表 JSON 文本；null=尚无译表（不注入灌表脚本）。 */
    @Volatile
    private var mapText: String? = null

    /** 运行时 JS 资产懒加载；缺失时注入侧静默降级（不影响游戏启动）。 */
    private val runtimeJs: String? by lazy {
        runCatching {
            context.assets.open(TranslationTable.RUNTIME_ASSET).buffered().use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure { Log.w(TAG, "runtime asset missing: ${TranslationTable.RUNTIME_ASSET}", it) }
            .getOrNull()
    }

    /** 会话启动：后台读译表 + 启动热更新监听（宿主在 WebView 就绪后调用）。 */
    fun attach() {
        Thread {
            val file = TranslationTable.resolveTranslationFile(context, gameId, gameDir, selectedLanguage)
            val text = file?.let { TranslationTable.readMapText(it) }
            if (file != null && text == null) {
                Log.w(TAG, "translation file unreadable: ${file.absolutePath}")
            }
            mapText = text
            Log.i(TAG, "attach gameId=$gameId file=${file?.absolutePath} pairs=${text?.length ?: 0} chars")
            if (file != null) startObserver(file)
            mainHandler.post { pushTranslations("attach") }
        }.apply {
            name = "TranslationTableLoader"
            isDaemon = true
            start()
        }
    }

    /** 页面开始：尽早注入运行时（幂等；onPageFinished 会再补一次）。 */
    fun onPageStarted() {
        injectRuntime()
    }

    /** 页面完成：幂等重注入运行时 + 补推一次译表（防早注入丢失）。 */
    fun onPageFinished() {
        injectRuntime()
        pushTranslations("pageFinished")
    }

    fun destroy() {
        runCatching { observer?.stopWatching() }
        observer = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun injectRuntime() {
        val js = runtimeJs ?: return
        mainHandler.post { evaluateJs(js, null) }
    }

    /** 灌表（主线程）；结果统计走 console 诊断通道打日志。 */
    private fun pushTranslations(reason: String) {
        val text = mapText ?: return
        val callback = ValueCallback<String> { result ->
            Log.i(TAG, "applied($reason): ${result?.take(300) ?: "null"}")
        }
        evaluateJs(TranslationTable.buildSetterJs(text), callback)
    }

    /**
     * 监听译表文件所在目录（而非文件本身）：编辑器/同步工具常以「写临时文件+重命名」
     * 原子替换，按目录观察 CLOSE_WRITE/MOVED_TO/DELETE 才不会丢事件。删除视为清除
     * 译表（回退原文）。
     */
    private fun startObserver(file: File) {
        val dir = file.parentFile ?: return
        val fileName = file.name
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE
        val newObserver = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path != fileName) return
                when (event) {
                    FileObserver.CLOSE_WRITE, FileObserver.MOVED_TO -> reloadTranslations()
                    FileObserver.DELETE -> clearTranslations()
                }
            }
        }
        observer = newObserver
        newObserver.startWatching()
    }

    private fun reloadTranslations() {
        val file = TranslationTable.resolveTranslationFile(context, gameId, gameDir, selectedLanguage) ?: return
        val text = TranslationTable.readMapText(file)
        if (text == null) {
            Log.w(TAG, "hot reload ignored (unreadable): ${file.absolutePath}")
            return
        }
        mapText = text
        mainHandler.post { pushTranslations("hotReload") }
    }

    private fun clearTranslations() {
        mapText = "{}"
        Log.i(TAG, "translation file deleted; falling back to original text")
        mainHandler.post { pushTranslations("deleted") }
    }
}
