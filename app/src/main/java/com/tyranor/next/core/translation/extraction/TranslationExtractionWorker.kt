package com.tyranor.next.core.translation.extraction

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

/** Resumable MV/MZ extraction. Room checkpoints and entry writes commit in the same transaction. */
class TranslationExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val dao = TranslationWorkbenchDatabase.get(appContext).translationDao()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        try {
            val task = dao.getTask(taskId) ?: return Result.failure()
            if (task.state == "CANCELLED") return Result.success()
            if (dao.markTaskRunning(taskId, System.currentTimeMillis()) != 1) return Result.success()

            val project = dao.getProject(task.projectId) ?: return fail(taskId, "PROJECT_NOT_FOUND")
            val treeUri = task.sourceTreeUri ?: return fail(taskId, "INVALID_GAME_DIRECTORY")
            val extractorId = task.extractorId ?: return fail(taskId, "UNSUPPORTED_ENGINE")
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
                if (!committed) return Result.success()
                completed += file
                setProgress(
                    workDataOf(
                        PROGRESS_COMPLETED_FILES to completed.size,
                        PROGRESS_TOTAL_FILES to files.size,
                    ),
                )
            }

            if (dao.getEntries(task.projectId).isEmpty()) return fail(taskId, ExtractionErrorCode.NO_TEXT_FOUND.name)
            dao.completeTask(taskId, System.currentTimeMillis())
            return Result.success()
        } catch (cancelled: CancellationException) {
            // A user cancellation is already persisted by the repository. OS/process stops leave RUNNING
            // untouched so WorkManager can rerun the same task and continue from its last committed file.
            throw cancelled
        } catch (failure: TranslationExtractionException) {
            return fail(taskId, failure.code.name)
        } catch (failure: Exception) {
            return fail(taskId, ExtractionErrorCode.STORAGE_FAILURE.name)
        }
    }

    private suspend fun fail(taskId: String, code: String): Result {
        dao.failTask(taskId, code, System.currentTimeMillis())
        dao.failQueuedTask(taskId, code, System.currentTimeMillis())
        return Result.failure(workDataOf(OUTPUT_FAILURE_CODE to code))
    }

    private fun decodeCheckpoint(json: String): Set<String> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNullTo(mutableSetOf()) { array.optString(it).takeIf(String::isNotBlank) }
    } catch (failure: Exception) {
        throw TranslationExtractionException(ExtractionErrorCode.STORAGE_FAILURE, failure)
    }

    companion object {
        const val KEY_TASK_ID = "translation_task_id"
        const val OUTPUT_FAILURE_CODE = "translation_failure_code"
        const val PROGRESS_COMPLETED_FILES = "completed_files"
        const val PROGRESS_TOTAL_FILES = "total_files"
        const val GLOBAL_QUEUE_NAME = "translation-workbench-content-tasks"
    }
}
