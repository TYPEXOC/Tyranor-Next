package com.tyranor.next.core.translation.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tyranor.next.core.settings.AppStorageNames

@Database(
    entities = [
        TranslationProjectEntity::class,
        TranslationEntryEntity::class,
        TranslationTaskEntity::class,
        TranslationExportEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class TranslationWorkbenchDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationWorkbenchDao

    companion object {
        @Volatile
        private var instance: TranslationWorkbenchDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE translation_projects ADD COLUMN project_state TEXT NOT NULL DEFAULT 'NEW'",
                )
                db.execSQL(
                    """UPDATE translation_projects SET project_state = CASE
                        WHEN (SELECT state FROM translation_tasks WHERE project_id = translation_projects.id
                            ORDER BY created_at DESC, id DESC LIMIT 1) IN ('QUEUED', 'RUNNING') THEN 'PROCESSING'
                        WHEN (SELECT state FROM translation_tasks WHERE project_id = translation_projects.id
                            ORDER BY created_at DESC, id DESC LIMIT 1) = 'FAILED' THEN 'FAILED'
                        WHEN (SELECT state FROM translation_tasks WHERE project_id = translation_projects.id
                            ORDER BY created_at DESC, id DESC LIMIT 1) = 'CANCELLED' THEN 'PAUSED'
                        WHEN source_type != 'GAME_DIRECTORY' OR EXISTS (
                            SELECT 1 FROM translation_entries WHERE project_id = translation_projects.id
                        ) THEN 'READY'
                        ELSE 'NEW'
                    END""".trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_exports` (
                        `id` TEXT NOT NULL,
                        `project_id` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `destination_name` TEXT,
                        `entry_count` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`project_id`) REFERENCES `translation_projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translation_exports_project_id_created_at` " +
                        "ON `translation_exports` (`project_id`, `created_at`)",
                )
            }
        }

        fun get(context: Context): TranslationWorkbenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TranslationWorkbenchDatabase::class.java,
                AppStorageNames.TRANSLATION_WORKBENCH_DATABASE,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
