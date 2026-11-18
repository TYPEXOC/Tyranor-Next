package com.tyranor.next.core.translation.extraction

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tyranor.next.core.translation.TranslationTaskNotification
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationTaskEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

/** Drains durable extraction tasks serially across projects. Room remains the progress authority. */
class TranslationExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val dao = TranslationWorkbenchDatabase.get(appContext).translationDao()

    override suspend fun doWork(): Result {
        try {
            var task = dao.getNextRunnableTask() ?: return Result.success()
            setForeground(TranslationTaskNotification.foregroundInfo(applicationContext, 0, 0))
            while (true) {
                currentCoroutineContext().ensureActive()
                processTask(task)
                task = dao.getNextRunnableTask() ?: return Result.success()
            }
        } catch (cancelled: CancellationException) {
            // WorkManager may recreate this drain after process/OS interruption. A RUNNING task is
            // deliberately left recoverable; user cancellation is already persisted in Room.
            throw cancelled
        } catch (failure: Exception) {
            return Result.retry()
        }
    }

    private suspend fun processTask(task: TranslationTaskEntity) {
        val taskId = task.id
        if (dao.markTaskRunning(taskId, System.currentTimeMillis()) != 1) return
        try {
            val project = dao.getProject(task.projectId)
                ?: return fail(taskId, "PROJECT_NOT_FOUND")
            val treeUri = task.sourceTreeUri
                ?: return fail(taskId, "INVALID_GAME_DIRECTORY")
            val extractorId = task.extractorId
                ?: return fail(taskId, "UNSUPPORTED_ENGINE")
            val extractor = TextExtractorRegistry.registeredDescriptors()
                .firstOrNull { it.extractorId == extractorId }
                ?.let { TextExtractorRegistry.find(it.engineId) }
                ?: return fail(taskId, "UNSUPPORTED_ENGINE")
            val source = SafReadOnlyGameSource(applicationContext, treeUri)
            val plan = extractor.preflight(source)
            val files = plan.files.distinct()
            dao.setTaskFileTotal(taskId, files.size, System.currentTimeMillis())

            val savedCheckpoint = decodeCheckpoint(task.checkpointJson)
            val completed = savedCheckpoint.intersect(files.toSet()).toMutableSet()
            setForeground(TranslationTaskNotification.foregroundInfo(applicationContext, completed.size, files.size))
            if (completed != savedCheckpoint) {
                dao.updateTaskCheckpoint(
                    taskId = taskId,
                    checkpointJson = JSONArray(completed.sorted()).toString(),
                    completedFiles = completed.size,
                    newEntries = 0,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (dao.getTask(taskId)?.state != "RUNNING") return
                if (file in completed) continue
                val extracted = extractor.extractFile(source, file)
                val rows = extracted.map { entry ->
                    TranslationEntryEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = task.projectId,
                        sourceKey = entry.sourceKey,
                        sourceText = entry.sourceText,
                        translatedText = null,
                        sourceFile = entry.sourceFile,
                        sourcePointer = entry.sourcePointer,
                        textType = entry.textType,
                        status = "UNTRANSLATED",
                    )
                }
                val nextCompleted = completed + file
                val committed = dao.commitExtractedFile(
                    projectId = task.projectId,
                    taskId = taskId,
                    checkpointJson = JSONArray(nextCompleted.sorted()).toString(),
                    completedFiles = nextCompleted.size,
                    entries = rows,
                    updatedAt = System.currentTimeMillis(),
                )
                if (!committed) return
                completed += file
                setForeground(TranslationTaskNotification.foregroundInfo(applicationContext, completed.size, files.size))
                setProgress(
                    workDataOf(
                        PROGRESS_COMPLETED_FILES to completed.size,
                        PROGRESS_TOTAL_FILES to files.size,
                    ),
                )
            }

            if (dao.getEntries(task.projectId).isEmpty()) {
                fail(taskId, ExtractionErrorCode.NO_TEXT_FOUND.name)
            } else {
                dao.completeTaskAndReadyProject(taskId, System.currentTimeMillis())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TranslationExtractionException) {
            fail(taskId, failure.code.name)
        } catch (failure: Exception) {
            fail(taskId, ExtractionErrorCode.STORAGE_FAILURE.name)
        }
    }

    private suspend fun fail(taskId: String, code: String) {
        dao.failTaskAndFailProject(taskId, code, System.currentTimeMillis())
    }

    private fun decodeCheckpoint(json: String): Set<String> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNullTo(mutableSetOf()) { array.optString(it).takeIf(String::isNotBlank) }
    } catch (failure: Exception) {
        throw TranslationExtractionException(ExtractionErrorCode.STORAGE_FAILURE, failure)
    }

    companion object {
        const val PROGRESS_COMPLETED_FILES = "completed_files"
        const val PROGRESS_TOTAL_FILES = "total_files"
        const val GLOBAL_QUEUE_NAME = "translation-workbench-content-tasks"
    }
}
