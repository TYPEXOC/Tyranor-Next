package com.tyranor.next.core.translation.service.protocol.machine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.translation.TranslationBatchTaskProcessor
import com.tyranor.next.core.translation.TranslationProjectSource
import com.tyranor.next.core.translation.TranslationProjectState
import com.tyranor.next.core.translation.TranslationTaskSnapshot
import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings.Companion.DEFAULT_MACHINE_BATCH_SIZE
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceDraft
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationProjectEntity
import com.tyranor.next.core.translation.storage.TranslationTaskEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-provider smoke coverage is opt-in because keyless web endpoints are third-party services. */
@RunWith(AndroidJUnit4::class)
class TranslationWebProviderLiveSmokeDeviceTest {
    @Test
    fun bingTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Bing", trace) {
            BingWebTranslationAdapter(tracingTransport(trace)).translateBatch(
                profile(TranslationProtocolId.BING_TRANSLATOR_WEB),
                null,
                "en",
                "zh-CN",
                listOf(input("bing", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("bing", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun bingProcessesDefaultBatchTaskAndPersistsCheckpoint() = runBlocking {
        assumeEnabled()
        processDefaultBatchTask("Bing", TranslationProtocolId.BING_TRANSLATOR_WEB)
    }

    @Test
    fun youdaoTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Youdao", trace) {
            YoudaoWebTranslationAdapter(tracingTransport(trace, inspectYoudaoResponse = true)).translateBatch(
                profile(TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1),
                null,
                "en",
                "zh-CN",
                listOf(input("youdao", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("youdao", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun youdaoProcessesDefaultBatchTaskAndPersistsCheckpoint() = runBlocking {
        assumeEnabled()
        processDefaultBatchTask("Youdao", TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1)
    }

    @Test
    fun googleTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Google", trace) {
            GoogleTranslateWebAdapter(tracingTransport(trace)).translateBatch(
                profile(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1),
                null,
                "en",
                "zh-CN",
                listOf(input("google", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("google", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun googleProcessesDefaultBatchTaskAndPersistsCheckpoint() = runBlocking {
        assumeEnabled()
        processDefaultBatchTask("Google", TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1)
    }

    private suspend fun processDefaultBatchTask(provider: String, protocol: TranslationProtocolId) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = TranslationWorkbenchDatabase.get(context).translationDao()
        val serviceRepository = TranslationServiceRepository(context)
        val now = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString()
        val projectId = "live-batch-project-$suffix"
        val sources = listOf(
            "Hello, traveler.",
            "Thank you for helping me.",
            "The train leaves at dawn.",
            "Please wait here until the guide returns.",
            "The village shop closes at sunset.",
            "I found a key near the old bridge.",
            "Our party will leave after breakfast.",
            "The northern road is blocked by snow.",
            "Could you tell me where the library is?",
            "We need three healing potions.",
            "The festival begins tomorrow morning.",
            "Mira promised to meet us at the harbor.",
            "This machine has been broken for years.",
            "Do not open the door during the storm.",
            "I heard footsteps in the empty hallway.",
            "The captain has already read your letter.",
            "Take the blue flowers to the healer.",
            "We should save our supplies for the journey.",
            "A small boat is waiting beside the pier.",
            "The mountain path is safer in daylight.",
            "Your brother left a message on the table.",
            "There are two switches behind the painting.",
            "The castle gate will open at noon.",
            "I cannot remember the password anymore.",
            "Please return the book before you leave.",
            "A stranger asked about the silver necklace.",
            "The next airship arrives in half an hour.",
            "We heard music coming from the basement.",
            "Keep this map away from the rain.",
            "The final decision belongs to the council.",
        )
        require(sources.size == DEFAULT_MACHINE_BATCH_SIZE)
        val entries = sources.mapIndexed { index, source ->
            TranslationEntryEntity(
                id = "live-batch-entry-$index-$suffix",
                projectId = projectId,
                sourceKey = "live-batch-$index",
                sourceText = source,
                translatedText = null,
                sourceFile = "smoke.json",
                sourcePointer = "entries[$index]",
                textType = "dialogue",
                status = "UNTRANSLATED",
            )
        }
        var serviceId: String? = null
        try {
            val profile = serviceRepository.saveProfile(
                TranslationServiceDraft(
                    displayName = "Live batch $provider $suffix",
                    protocolId = protocol,
                    baseUrl = protocol.defaultBaseUrl(),
                    modelId = "",
                    enabled = true,
                    translationPrompt = DEFAULT_TRANSLATION_PROMPT,
                    taskSettings = TranslationServiceTaskSettings(),
                ),
            )
            serviceId = profile.id
            dao.upsertProject(
                TranslationProjectEntity(
                    id = projectId,
                    identity = "live-batch:$suffix",
                    exchangeKey = "live-batch-exchange:$suffix",
                    title = "Live batch $provider",
                    projectState = TranslationProjectState.READY.name,
                    engineId = null,
                    sourceType = TranslationProjectSource.IMPORTED_JSON.name,
                    sourceTreeUri = null,
                    sourceLanguage = null,
                    targetLanguage = null,
                    revision = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.insertEntries(entries)
            val snapshot = TranslationTaskSnapshot(
                serviceId = profile.id,
                serviceRevision = profile.revision,
                protocolId = protocol,
                baseUrl = profile.baseUrl,
                modelId = profile.modelId,
                secretRef = profile.secretRef,
                sourceLanguage = "en",
                targetLanguage = "zh-CN",
                prompt = profile.translationPrompt,
                glossaryRevision = 0,
                glossary = emptyList(),
                taskSettings = profile.taskSettings,
            )
            val task = TranslationTaskEntity(
                id = "live-batch-task-$suffix",
                projectId = projectId,
                type = "TRANSLATE",
                state = "QUEUED",
                extractorId = null,
                sourceTreeUri = null,
                configSnapshotJson = snapshot.encode(),
                checkpointJson = "{}",
                totalFiles = 0,
                completedFiles = 0,
                entryCount = 0,
                failureCode = null,
                createdAt = now,
                updatedAt = now,
            )
            assertTrue(dao.createTranslationTaskIfProjectIdle(task, "en", "zh-CN", null, 0L))

            val progress = mutableListOf<Pair<Int, Int>>()
            TranslationBatchTaskProcessor(context, dao) { completed, total ->
                progress += completed to total
            }.process(task)

            val completedTask = requireNotNull(dao.getTask(task.id))
            assertEquals(
                "$provider three-entry task failed with ${completedTask.failureCode}",
                "COMPLETED",
                completedTask.state,
            )
            assertEquals(sources.size, completedTask.itemTotal)
            assertEquals(sources.size, completedTask.completedItems)
            assertEquals(0, completedTask.failedItems)
            val checkpointIds = JSONObject(completedTask.checkpointJson)
                .getJSONArray("processedEntryIds")
                .let { values -> (0 until values.length()).map(values::getString).toSet() }
            assertEquals(entries.map(TranslationEntryEntity::id).toSet(), checkpointIds)
            assertEquals(sources.size to sources.size, progress.last())

            val translatedEntries = dao.getEntries(projectId).associateBy(TranslationEntryEntity::id)
            entries.forEachIndexed { index, entry ->
                val translated = requireNotNull(translatedEntries[entry.id])
                assertEquals("TRANSLATED", translated.status)
                assertFalse(translated.translatedText.isNullOrBlank())
                assertFalse("$provider returned the source text unchanged", translated.translatedText == sources[index])
            }
        } finally {
            dao.deleteProject(projectId)
            serviceId?.let { serviceRepository.deleteProfile(it) }
        }
    }

    private fun assumeEnabled() = assumeTrue(
        "Pass translation_web_live_smoke=true to contact the live translation endpoints",
        InstrumentationRegistry.getArguments().getString("translation_web_live_smoke")?.toBoolean() == true,
    )

    private fun profile(protocol: TranslationProtocolId) = TranslationServiceProfile(
        id = "live-smoke-${protocol.wireId}",
        displayName = "Live smoke test",
        protocolId = protocol,
        baseUrl = protocol.defaultBaseUrl(),
        modelId = "",
        enabled = true,
        configured = true,
        hasSecret = false,
        revision = 1,
        translationPrompt = "",
        secretRef = null,
        updatedAt = 0,
    )

    private fun input(id: String, text: String) = TranslationInput(id, text, "en", "zh-CN")

    private suspend fun <T> withProviderDiagnostics(
        provider: String,
        trace: List<String>,
        action: suspend () -> T,
    ): T = try {
        action()
    } catch (failure: TranslationServiceException) {
        throw AssertionError(
            "$provider live translation failed (${failure.code}); requests: ${trace.joinToString("; ")}",
        )
    }

    private fun tracingTransport(
        trace: MutableList<String>,
        inspectYoudaoResponse: Boolean = false,
    ): TranslationHttpTransport {
        var youdaoAesKey: String? = null
        var youdaoAesIv: String? = null
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val endpoint = "${request.method} ${request.url.host}${request.url.encodedPath}"
                val startedAt = System.nanoTime()
                try {
                    chain.proceed(request).also { response ->
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                        var detail = ""
                        if (request.url.host in BING_DIAGNOSTIC_HOSTS && request.url.encodedPath == "/translator" && response.isSuccessful) {
                            val page = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            detail = "; responseBytes=${page.toByteArray().size}" +
                                "; markers=ig:${page.contains("\"ig\"")},iid:${page.contains("data-iid")},token:${page.contains("params_AbusePreventionHelper")}" +
                                "; tokenArray=${Regex("var params_AbusePreventionHelper\\s*=\\s*\\[[^\\]]+]").containsMatchIn(page)}"
                        }
                        if (request.url.host in BING_DIAGNOSTIC_HOSTS && request.url.encodedPath == "/ttranslatev3" && response.isSuccessful) {
                            val body = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            val value = runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull()
                            val first = (value as? org.json.JSONArray)?.optJSONObject(0)
                            val translations = first?.optJSONArray("translations")
                            val firstTranslation = translations?.optJSONObject(0)
                            detail = "; responseBytes=${body.toByteArray().size}" +
                                "; responseShape=${when (value) {
                                    is org.json.JSONArray -> "array(${value.length()})"
                                    is org.json.JSONObject -> "object"
                                    is String -> "string(${value.length})"
                                    null -> "invalid-json"
                                    else -> value::class.java.simpleName
                                }}" +
                                "; fields=${first?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"}" +
                                "; statusCode=${(value as? org.json.JSONObject)?.optInt("statusCode", -1) ?: -1}" +
                                "; translationFields=${firstTranslation?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"}" +
                                "; textLength=${firstTranslation?.optString("text")?.length ?: 0}"
                        }
                        if (inspectYoudaoResponse && response.isSuccessful) {
                            val responseBody = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            if (request.url.encodedPath.endsWith("/webtranslate/key")) {
                                var keyResponseCode = -1
                                var keyFields = "missing"
                                runCatching {
                                    val keyResponse = org.json.JSONObject(responseBody)
                                    keyResponseCode = keyResponse.optInt("code", -1)
                                    val data = keyResponse.optJSONObject("data")
                                    youdaoAesKey = data?.optString("aesKey")?.takeIf(String::isNotBlank)
                                    youdaoAesIv = data?.optString("aesIv")?.takeIf(String::isNotBlank)
                                    keyFields = data?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"
                                }
                                detail = "; keyCode=$keyResponseCode; keyFields=$keyFields" +
                                    if (youdaoAesKey != null && youdaoAesIv != null) "; session material received" else "; session material missing"
                            } else if (request.url.encodedPath.endsWith("/webtranslate")) {
                                val encodedBody = responseBody.trim()
                                val decryptedResult = runCatching {
                                    YoudaoWebTranslationAdapter.parseEncryptedResponseForTest(
                                        encodedBody,
                                        youdaoAesKey.orEmpty(),
                                        youdaoAesIv.orEmpty(),
                                    )
                                }
                                val payload = decryptedResult.getOrNull()
                                    ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                                val translated = payload?.optJSONArray("translateResult")
                                detail = if (payload != null) {
                                    "; decryptedCode=${payload.optInt("code", -1)}" +
                                        "; resultType=${translated?.let { "array(${it.length()})" } ?: "missing"}"
                                } else {
                                    val encodedBytes = runCatching {
                                        java.util.Base64.getDecoder().decode(encodedBody).size
                                    }.getOrNull()
                                    val jsonValue = runCatching { org.json.JSONTokener(encodedBody).nextValue() }.getOrNull()
                                    val plainJson = jsonValue as? org.json.JSONObject
                                    val jsonKeys = plainJson?.keys()?.asSequence()?.sorted()?.joinToString(",")
                                    val jsonShape = when (jsonValue) {
                                        is org.json.JSONObject -> "object"
                                        is org.json.JSONArray -> "array(${jsonValue.length()})"
                                        is String -> "string(${jsonValue.length})"
                                        null -> "invalid-json"
                                        else -> jsonValue::class.java.simpleName
                                    }
                                    val diagnosticText = jsonValue as? String ?: encodedBody
                                    val candidateBase64Bytes = runCatching {
                                        java.util.Base64.getDecoder().decode(diagnosticText).size
                                    }.getOrNull()
                                    val candidateBase64UrlBytes = runCatching {
                                        java.util.Base64.getUrlDecoder().decode(diagnosticText).size
                                    }.getOrNull()
                                    val responseCategory = when {
                                        diagnosticText.trimStart().startsWith("<") -> "html"
                                        diagnosticText.contains("captcha", ignoreCase = true) ||
                                            diagnosticText.contains("verify", ignoreCase = true) ||
                                            diagnosticText.contains("验证码") -> "verification-text"
                                        diagnosticText.contains("error", ignoreCase = true) || diagnosticText.contains("错误") -> "error-text"
                                        else -> "opaque-text"
                                    }
                                    val nestedJson = (jsonValue as? String)
                                        ?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() }
                                    val nestedObject = nestedJson as? org.json.JSONObject
                                    val nestedKeys = nestedObject?.keys()?.asSequence()?.sorted()?.joinToString(",")
                                    val nestedShape = when (nestedJson) {
                                        is org.json.JSONObject -> "object"
                                        is org.json.JSONArray -> "array(${nestedJson.length()})"
                                        is String -> "string(${nestedJson.length})"
                                        null -> "not-json"
                                        else -> nestedJson::class.java.simpleName
                                    }
                                    "; responseBytes=${responseBody.toByteArray().size}" +
                                        "; contentType=${response.header("Content-Type")?.substringBefore(';') ?: "missing"}" +
                                        "; responseShape=$jsonShape" +
                                        "; responseCategory=$responseCategory" +
                                        "; nestedShape=$nestedShape" +
                                        "; base64Bytes=${encodedBytes ?: "invalid"}" +
                                        "; unwrappedBase64Bytes=${candidateBase64Bytes ?: "invalid"}" +
                                        "; unwrappedBase64UrlBytes=${candidateBase64UrlBytes ?: "invalid"}" +
                                        "; decryptFailure=${decryptedResult.exceptionOrNull()?.javaClass?.simpleName ?: "invalid JSON"}" +
                                        (plainJson?.let {
                                            "; plainJsonCode=${it.optInt("code", -1)}; keys=$jsonKeys; msgLength=${it.optString("msg").length}"
                                        } ?: nestedObject?.let {
                                            "; nestedCode=${it.optInt("code", -1)}; nestedKeys=$nestedKeys; msgLength=${it.optString("msg").length}"
                                        }.orEmpty())
                                }
                            }
                        }
                        val location = response.header("Location")?.let(::safeLocation)
                        if (location != null) detail += "; location=$location"
                        trace += "$endpoint -> HTTP ${response.code} (${elapsedMs}ms)$detail"
                    }
                } catch (failure: IOException) {
                    trace += "$endpoint -> ${failure.javaClass.simpleName}"
                    throw failure
                }
            }
            .build()
        return TranslationHttpTransport(client)
    }

    private companion object {
        const val MAX_DIAGNOSTIC_BODY_BYTES = 2 * 1024 * 1024L
        val BING_DIAGNOSTIC_HOSTS = setOf("www.bing.com", "cn.bing.com")
    }

    private fun safeLocation(value: String): String = runCatching {
        val url = value.toHttpUrl()
        "${url.host}${url.encodedPath}"
    }.getOrDefault("invalid")
}
