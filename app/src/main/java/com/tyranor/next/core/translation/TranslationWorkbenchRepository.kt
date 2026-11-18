package com.tyranor.next.core.translation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationProjectEntity
import com.tyranor.next.core.translation.storage.TranslationProjectCardRow
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import com.tyranor.next.core.translation.storage.TranslationTaskEntity
import com.tyranor.next.core.translation.storage.TranslationExportEntity
import com.tyranor.next.core.translation.storage.TranslationExportRow
import com.tyranor.next.core.translation.storage.TranslationGlossaryTermEntity
import com.tyranor.next.core.translation.extraction.TextExtractorRegistry
import com.tyranor.next.core.translation.extraction.TranslationExtractionWorker
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.export.mtool.MToolJsonExporter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Project and exchange operations for the translation workbench. UI text is mapped outside core. */
class TranslationWorkbenchRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = TranslationWorkbenchDatabase.get(appContext).translationDao()
    private val serviceRepository = TranslationServiceRepository(appContext)

    fun observeProjects(): Flow<List<TranslationProjectCard>> =
        dao.observeProjectCards().map { rows -> rows.map(TranslationProjectCardRow::toModel) }

    fun observeEntries(projectId: String): Flow<List<TranslationTextEntry>> =
        dao.observeEntries(projectId).map { rows ->
            rows.map { row ->
                TranslationTextEntry(
                    id = row.id,
                    sourceKey = row.sourceKey,
                    sourceText = row.sourceText,
                    translatedText = row.translatedText,
                    sourceFile = row.sourceFile,
                    sourcePointer = row.sourcePointer,
                    textType = row.textType,
                )
            }
        }

    fun observeExportHistory(projectId: String): Flow<List<TranslationExportRecord>> =
        dao.observeExportHistory(projectId).map { rows ->
            rows.map { row ->
                TranslationExportRecord(
                    id = row.id,
                    format = runCatching { TranslationProjectFormat.valueOf(row.format) }
                        .getOrDefault(TranslationProjectFormat.JSON),
                    destinationName = row.destinationName,
                    entryCount = row.entryCount,
                    createdAt = row.createdAt,
                )
            }
        }

    fun observeEnabledTranslationServices(): Flow<List<TranslationServiceProfile>> =
        serviceRepository.observeEnabled(TranslationServiceCapability.TRANSLATE)

    fun observeGlossary(projectId: String): Flow<List<TranslationGlossaryTerm>> =
        dao.observeGlossary(projectId).map { terms ->
            terms.map { TranslationGlossaryTerm(it.sourceTerm, it.targetTerm, it.note) }
        }

    suspend fun saveGlossary(projectId: String, draftTerms: List<TranslationGlossaryTerm>): Long {
        val project = dao.getProject(projectId)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
        if (dao.getActiveTask(projectId) != null) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_ALREADY_ACTIVE)
        }
        val terms = draftTerms.map { term ->
            TranslationGlossaryTerm(
                sourceTerm = term.sourceTerm.trim(),
                targetTerm = term.targetTerm.trim(),
                note = term.note?.trim()?.takeIf(String::isNotBlank),
            )
        }.filter { it.sourceTerm.isNotEmpty() || it.targetTerm.isNotEmpty() }
        if (terms.size > MAX_GLOSSARY_TERMS || terms.any {
                it.sourceTerm.isBlank() || it.targetTerm.isBlank() ||
                    it.sourceTerm.length > MAX_GLOSSARY_TERM_LENGTH || it.targetTerm.length > MAX_GLOSSARY_TERM_LENGTH ||
                    (it.note?.length ?: 0) > MAX_GLOSSARY_NOTE_LENGTH
            } || terms.sumOf { it.sourceTerm.length + it.targetTerm.length + (it.note?.length ?: 0) } > MAX_GLOSSARY_CHARS
        ) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_GLOSSARY)
        }
        if (terms.groupingBy { it.sourceTerm.lowercase(Locale.ROOT) }.eachCount().any { it.value > 1 }) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.DUPLICATE_GLOSSARY_TERM)
        }
        val now = System.currentTimeMillis()
        val rows = terms.map { term ->
            TranslationGlossaryTermEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                sourceTerm = term.sourceTerm,
                targetTerm = term.targetTerm,
                note = term.note,
                updatedAt = now,
            )
        }
        return dao.replaceGlossary(projectId, rows, now)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
    }

    /** Creates one project for the selected SAF tree; an existing tree returns its existing project id. */
    suspend fun createGameDirectoryProject(treeUri: Uri, engineId: String): ProjectCreation {
        val engine = runCatching { EngineType.valueOf(engineId) }.getOrNull()
        if (engine != EngineType.RPG_MV && engine != EngineType.RPG_MZ) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_ENGINE)
        }
        val normalized = treeUri.normalizeScheme()
        val sourceIdentity = directoryIdentity(normalized)
        val title = withContext(Dispatchers.IO) {
            DocumentFile.fromTreeUri(appContext, normalized)?.name?.takeIf(String::isNotBlank)
        } ?: normalized.lastPathSegment?.substringAfterLast(':')?.takeIf(String::isNotBlank)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_GAME_DIRECTORY)
        val now = System.currentTimeMillis()
        val project = TranslationProjectEntity(
            id = UUID.randomUUID().toString(),
            identity = sourceIdentity,
            exchangeKey = UUID.randomUUID().toString(),
            title = title,
            projectState = TranslationProjectState.NEW.name,
            engineId = engine.name,
            sourceType = TranslationProjectSource.GAME_DIRECTORY.name,
            sourceTreeUri = normalized.toString(),
            sourceLanguage = null,
            targetLanguage = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = dao.insertProjectIfAbsent(project)
        val actual = if (inserted == -1L) {
            dao.getProjectByIdentity(sourceIdentity)
                ?: throw TranslationWorkbenchException(TranslationWorkbenchError.STORAGE_FAILURE)
        } else {
            project
        }
        return ProjectCreation(actual.id, created = inserted != -1L)
    }

    /** Imports a standard CSV or versioned TYN project JSON into private Room storage. */
    suspend fun importDocument(uri: Uri, displayName: String, format: TranslationProjectFormat): String =
        withContext(Dispatchers.IO) {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
            val text = input.use { readBoundedText(it) }
            val fallbackTitle = displayName.substringBeforeLast('.', displayName).ifBlank { displayName }
            val archive = when (format) {
                TranslationProjectFormat.JSON -> TranslationProjectCodec.decodeJson(text)
                TranslationProjectFormat.CSV -> TranslationProjectCodec.decodeCsv(text, fallbackTitle)
                TranslationProjectFormat.MTOOL_JSON ->
                    throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)
            }
            val sourceType = when (format) {
                TranslationProjectFormat.CSV -> TranslationProjectSource.IMPORTED_CSV
                TranslationProjectFormat.JSON -> TranslationProjectSource.IMPORTED_JSON
                TranslationProjectFormat.MTOOL_JSON ->
                    throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT)
            }
            val engine = archive.engineId?.takeIf { value ->
                runCatching { EngineType.valueOf(value) }.isSuccess
            }
            val identity = importedIdentity(archive, engine)
            val now = System.currentTimeMillis()
            val projectId = UUID.randomUUID().toString()
            val project = TranslationProjectEntity(
                id = projectId,
                identity = identity,
                exchangeKey = archive.projectKey?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                title = archive.title.ifBlank { fallbackTitle },
                projectState = TranslationProjectState.READY.name,
                engineId = engine,
                sourceType = sourceType.name,
                sourceTreeUri = null,
                sourceLanguage = archive.sourceLanguage,
                targetLanguage = archive.targetLanguage,
                revision = 1,
                createdAt = now,
                updatedAt = now,
            )
            val entries = archive.entries.map { entry ->
                TranslationEntryEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    sourceKey = entry.sourceKey,
                    sourceText = entry.sourceText,
                    translatedText = entry.translatedText,
                    sourceFile = entry.sourceFile,
                    sourcePointer = entry.sourcePointer,
                    textType = entry.textType,
                    status = if (entry.translatedText.isNullOrBlank()) "UNTRANSLATED" else "TRANSLATED",
                )
            }
            try {
                dao.importProject(project, entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TranslationWorkbenchException) {
                throw failure
            } catch (failure: Exception) {
                if (dao.getProjectByIdentity(identity) != null) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_ALREADY_EXISTS, failure)
                }
                throw TranslationWorkbenchException(TranslationWorkbenchError.STORAGE_FAILURE, failure)
            }
            projectId
        }

    /** Writes only source/translation data to a user-selected destination. */
    suspend fun exportProject(projectId: String, uri: Uri, format: TranslationProjectFormat) =
        withContext(Dispatchers.IO) {
            val project = dao.getProject(projectId)
                ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
            val entries = dao.getEntries(projectId)
            val archive = TranslationProjectArchive(
                projectKey = project.exchangeKey,
                title = project.title,
                engineId = project.engineId,
                sourceLanguage = project.sourceLanguage,
                targetLanguage = project.targetLanguage,
                entries = entries.map { entry ->
                    TranslationArchiveEntry(
                        sourceKey = entry.sourceKey,
                        sourceText = entry.sourceText,
                        translatedText = entry.translatedText,
                        sourceFile = entry.sourceFile,
                        sourcePointer = entry.sourcePointer,
                        textType = entry.textType,
                    )
                },
            )
            val mtoolExport = if (format == TranslationProjectFormat.MTOOL_JSON) {
                MToolJsonExporter.export(project.engineId, archive.entries)
            } else {
                null
            }
            val text = when (format) {
                TranslationProjectFormat.CSV -> TranslationProjectCodec.encodeCsv(archive)
                TranslationProjectFormat.JSON -> TranslationProjectCodec.encodeJson(archive)
                TranslationProjectFormat.MTOOL_JSON -> mtoolExport!!.text
            }
            try {
                val output = appContext.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Output stream unavailable")
                output.use { stream -> stream.write(text.toByteArray(StandardCharsets.UTF_8)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.EXPORT_FAILED, failure)
            }
            val exportedAt = System.currentTimeMillis()
            try {
                withContext(NonCancellable) {
                    dao.recordExport(
                        TranslationExportEntity(
                            id = UUID.randomUUID().toString(),
                            projectId = projectId,
                            format = format.name,
                            destinationName = queryDocumentName(uri),
                            entryCount = mtoolExport?.entryCount ?: entries.size,
                            createdAt = exportedAt,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.EXPORT_HISTORY_FAILED, failure)
            }
        }

    suspend fun deleteProject(projectId: String) {
        val cancelledTasks = dao.cancelProjectTasks(projectId, System.currentTimeMillis())
        if (cancelledTasks > 0) cancelQueueWorkerAndRestart(enqueueAfterCancel = false)
        if (dao.deleteProject(projectId) == 0) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
        }
        try {
            serviceRepository.cleanupUnusedSecrets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Project deletion is already committed; secret pruning can be retried on a later profile operation.
        }
        ensureExtractionQueue()
    }

    suspend fun relinkGameDirectory(projectId: String, treeUri: Uri) {
        val project = dao.getProject(projectId)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
        if (project.sourceType != TranslationProjectSource.GAME_DIRECTORY.name) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_GAME_DIRECTORY)
        }
        if (dao.getActiveTask(projectId) != null) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_ALREADY_ACTIVE)
        }
        val uri = treeUri.normalizeScheme()
        dao.upsertProject(project.copy(sourceTreeUri = uri.toString(), updatedAt = System.currentTimeMillis()))
    }

    /** Enqueues one extract task in the app-wide serial work chain. */
    suspend fun startExtraction(projectId: String): TranslationTaskScheduleResult =
        scheduleExtraction(projectId, continueFromTaskId = null)

    /** Creates a new task that resumes a failed/cancelled task from its durable file checkpoint. */
    suspend fun continueExtraction(projectId: String, previousTaskId: String): TranslationTaskScheduleResult =
        scheduleExtraction(projectId, continueFromTaskId = previousTaskId)

    suspend fun startTranslation(
        projectId: String,
        serviceId: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationTaskScheduleResult {
        val source = sourceLanguage.trim()
        val target = targetLanguage.trim()
        if (source.isBlank() || target.isBlank() || source.length > MAX_LANGUAGE_LENGTH ||
            target.length > MAX_LANGUAGE_LENGTH || source.equals(target, ignoreCase = true)
        ) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_LANGUAGE_PAIR)
        }
        val schedule = serviceRepository.withProfileSnapshot(serviceId) { service ->
            if (!service.enabled || !service.configured ||
                service.protocolId != TranslationProtocolId.OPENAI_COMPATIBLE_CHAT
            ) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.TRANSLATION_SERVICE_UNAVAILABLE)
            }
            repeat(MAX_TRANSLATION_SNAPSHOT_ATTEMPTS) {
                val project = dao.getProject(projectId)
                    ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
                val active = dao.getActiveTask(projectId)
                if (active != null) return@withProfileSnapshot TranslationTaskScheduleResult.AlreadyActive(active.id)
                if (dao.countTranslationCandidates(projectId, retryFailed = false) == 0) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.NO_TRANSLATION_CANDIDATES)
                }
                val glossary = dao.getGlossary(projectId)
                val snapshot = TranslationTaskSnapshot(
                    serviceId = service.id,
                    serviceRevision = service.revision,
                    protocolId = service.protocolId,
                    baseUrl = service.baseUrl,
                    modelId = service.modelId,
                    secretRef = service.secretRef,
                    sourceLanguage = source,
                    targetLanguage = target,
                    prompt = service.translationPrompt,
                    glossaryRevision = project.glossaryRevision,
                    glossary = glossary.map { TranslationGlossaryTerm(it.sourceTerm, it.targetTerm, it.note) },
                )
                val snapshotJson = snapshot.encode()
                if (snapshotJson.toByteArray(StandardCharsets.UTF_8).size > MAX_TASK_SNAPSHOT_BYTES) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_GLOSSARY)
                }
                val now = System.currentTimeMillis()
                val taskId = UUID.randomUUID().toString()
                val task = TranslationTaskEntity(
                    id = taskId,
                    projectId = projectId,
                    type = TranslationExtractionWorker.TASK_TYPE_TRANSLATE,
                    state = "QUEUED",
                    extractorId = null,
                    sourceTreeUri = null,
                    configSnapshotJson = snapshotJson,
                    checkpointJson = "{}",
                    totalFiles = 0,
                    completedFiles = 0,
                    entryCount = 0,
                    failureCode = null,
                    createdAt = now,
                    updatedAt = now,
                )
                if (dao.createTranslationTaskIfProjectIdle(task, source, target, project.glossaryRevision)) {
                    return@withProfileSnapshot TranslationTaskScheduleResult.Started(taskId)
                }
                val queuedTask = dao.getActiveTask(projectId)
                if (queuedTask != null) return@withProfileSnapshot TranslationTaskScheduleResult.AlreadyActive(queuedTask.id)
                if (dao.getProject(projectId)?.glossaryRevision != project.glossaryRevision) return@repeat
                throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
            }
            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_CONFIGURATION_CHANGED)
        }
        if (schedule is TranslationTaskScheduleResult.Started) enqueueTaskQueueOrFail(schedule.taskId)
        return schedule
    }

    suspend fun continueTranslation(projectId: String, previousTaskId: String): TranslationTaskScheduleResult {
        val previous = dao.getTask(previousTaskId)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_NOT_ACTIVE)
        if (previous.projectId != projectId || previous.type != TranslationExtractionWorker.TASK_TYPE_TRANSLATE) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_NOT_ACTIVE)
        }
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        val task = TranslationTaskEntity(
            id = taskId,
            projectId = projectId,
            type = TranslationExtractionWorker.TASK_TYPE_TRANSLATE,
            state = "QUEUED",
            extractorId = null,
            sourceTreeUri = null,
            configSnapshotJson = previous.configSnapshotJson,
            checkpointJson = "{}",
            totalFiles = 0,
            completedFiles = 0,
            entryCount = 0,
            failureCode = null,
            createdAt = now,
            updatedAt = now,
        )
        if (!dao.createTranslationRetryTask(task, previousTaskId)) {
            val active = dao.getActiveTask(projectId)
            if (active != null) return TranslationTaskScheduleResult.AlreadyActive(active.id)
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_NOT_ACTIVE)
        }
        enqueueTaskQueueOrFail(taskId)
        return TranslationTaskScheduleResult.Started(taskId)
    }

    suspend fun cancelExtraction(taskId: String) {
        if (dao.cancelTaskAndPauseProject(taskId, System.currentTimeMillis()) != 1) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.TASK_NOT_ACTIVE)
        }
        cancelQueueWorkerAndRestart()
    }

    suspend fun cancelTask(taskId: String) = cancelExtraction(taskId)

    /** Reschedules a drain worker when durable queued/running tasks outlive a previous app process. */
    suspend fun ensureExtractionQueue() {
        if (!dao.hasRunnableTasks()) return
        try {
            enqueueQueueWorker()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.STORAGE_FAILURE, failure)
        }
    }

    private suspend fun scheduleExtraction(
        projectId: String,
        continueFromTaskId: String?,
    ): TranslationTaskScheduleResult {
        val project = dao.getProject(projectId)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_NOT_FOUND)
        if (project.sourceType != TranslationProjectSource.GAME_DIRECTORY.name || project.sourceTreeUri == null) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_GAME_DIRECTORY)
        }
        val engineId = project.engineId ?: throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_ENGINE)
        val extractor = TextExtractorRegistry.find(engineId)
            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.UNSUPPORTED_ENGINE)
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        val snapshot = JSONObject()
            .put("engineId", engineId)
            .put("extractorId", extractor.descriptor.extractorId)
            .put("formatVersion", extractor.descriptor.formatVersion)
            .put("outputSchemaVersion", extractor.descriptor.outputSchemaVersion)
            .toString()
        val task = TranslationTaskEntity(
            id = taskId,
            projectId = projectId,
            type = "EXTRACT",
            state = "QUEUED",
            extractorId = extractor.descriptor.extractorId,
            sourceTreeUri = project.sourceTreeUri,
            configSnapshotJson = snapshot,
            checkpointJson = "[]",
            totalFiles = 0,
            completedFiles = 0,
            entryCount = 0,
            failureCode = null,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = if (continueFromTaskId == null) {
            dao.createTaskIfProjectIdle(task)
        } else {
            dao.createContinuationTask(task, continueFromTaskId)
        }
        if (!inserted) {
            val active = dao.getActiveTask(projectId)
            if (active != null) return TranslationTaskScheduleResult.AlreadyActive(active.id)
            throw TranslationWorkbenchException(
                if (continueFromTaskId == null) TranslationWorkbenchError.TASK_ALREADY_ACTIVE
                else TranslationWorkbenchError.TASK_NOT_ACTIVE,
            )
        }
        enqueueTaskQueueOrFail(taskId)
        return TranslationTaskScheduleResult.Started(taskId)
    }

    private suspend fun enqueueTaskQueueOrFail(taskId: String) {
        try {
            enqueueQueueWorker()
        } catch (cancelled: CancellationException) {
            dao.failQueuedTaskAndFailProject(taskId, TranslationWorkbenchError.STORAGE_FAILURE.name, System.currentTimeMillis())
            throw cancelled
        } catch (failure: Exception) {
            dao.failQueuedTaskAndFailProject(taskId, TranslationWorkbenchError.STORAGE_FAILURE.name, System.currentTimeMillis())
            throw TranslationWorkbenchException(TranslationWorkbenchError.STORAGE_FAILURE, failure)
        }
    }

    private suspend fun cancelQueueWorkerAndRestart(enqueueAfterCancel: Boolean = true) {
        try {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(appContext)
                    .cancelUniqueWork(TranslationExtractionWorker.GLOBAL_QUEUE_NAME)
                    .result
                    .get()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw TranslationWorkbenchException(TranslationWorkbenchError.STORAGE_FAILURE, failure)
        }
        try {
            serviceRepository.cleanupUnusedSecrets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep task state authoritative even if pruning an obsolete encrypted key fails.
        }
        if (enqueueAfterCancel) ensureExtractionQueue()
    }

    private fun directoryIdentity(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return "tree:${uri.authority.orEmpty()}:${documentId ?: uri.toString()}"
    }

    private fun importedIdentity(archive: TranslationProjectArchive, validEngineId: String?): String {
        val key = archive.projectKey?.takeIf(String::isNotBlank)
        if (key != null) return "import-key:$key"
        val normalizedTitle = archive.title.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return "import-title:${validEngineId.orEmpty()}:$normalizedTitle"
    }

    private fun readBoundedText(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > TranslationProjectCodec.MAX_DOCUMENT_CHARS) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun queryDocumentName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun enqueueQueueWorker() {
        val request = OneTimeWorkRequestBuilder<TranslationExtractionWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            TranslationExtractionWorker.GLOBAL_QUEUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        private const val MAX_LANGUAGE_LENGTH = 64
        private const val MAX_GLOSSARY_TERMS = 100
        private const val MAX_GLOSSARY_TERM_LENGTH = 120
        private const val MAX_GLOSSARY_NOTE_LENGTH = 500
        private const val MAX_GLOSSARY_CHARS = 8_000
        private const val MAX_TASK_SNAPSHOT_BYTES = 64 * 1024
        private const val MAX_TRANSLATION_SNAPSHOT_ATTEMPTS = 3
    }
}

data class ProjectCreation(val projectId: String, val created: Boolean)

sealed interface TranslationTaskScheduleResult {
    data class Started(val taskId: String) : TranslationTaskScheduleResult
    data class AlreadyActive(val taskId: String) : TranslationTaskScheduleResult
}
