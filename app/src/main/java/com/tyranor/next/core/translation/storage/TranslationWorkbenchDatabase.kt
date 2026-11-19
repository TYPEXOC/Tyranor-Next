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
        TranslationServiceProfileEntity::class,
        TranslationGlossaryTermEntity::class,
        TranslationGlossaryEntity::class,
        TranslationGlossaryEntryEntity::class,
        TranslationProofreadSuggestionEntity::class,
        TranslationReviewIssueEntity::class,
    ],
    version = 7,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_service_profiles` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `protocol_id` TEXT NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `model_id` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `translation_prompt` TEXT NOT NULL,
                        `secret_ref` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translation_service_profiles_updated_at` " +
                        "ON `translation_service_profiles` (`updated_at`)",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_projects ADD COLUMN glossary_revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_entries ADD COLUMN failure_code TEXT")
                db.execSQL("ALTER TABLE translation_tasks ADD COLUMN item_total INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_tasks ADD COLUMN completed_items INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_tasks ADD COLUMN failed_items INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_glossary_terms` (
                        `id` TEXT NOT NULL,
                        `project_id` TEXT NOT NULL,
                        `source_term` TEXT NOT NULL,
                        `target_term` TEXT NOT NULL,
                        `note` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`project_id`) REFERENCES `translation_projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translation_glossary_terms_project_id` " +
                        "ON `translation_glossary_terms` (`project_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_translation_glossary_terms_project_id_source_term` " +
                        "ON `translation_glossary_terms` (`project_id`, `source_term`)",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_projects ADD COLUMN review_completed_at INTEGER")
                db.execSQL("ALTER TABLE translation_entries ADD COLUMN review_state TEXT NOT NULL DEFAULT 'UNREVIEWED'")
                db.execSQL("ALTER TABLE translation_entries ADD COLUMN last_proofread_task_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_entries_project_id_last_proofread_task_id` ON `translation_entries` (`project_id`, `last_proofread_task_id`)")
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN proofreading_prompt TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_proofread_suggestions` (
                        `id` TEXT NOT NULL,
                        `project_id` TEXT NOT NULL,
                        `entry_id` TEXT NOT NULL,
                        `task_id` TEXT NOT NULL,
                        `suggested_translation` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`project_id`) REFERENCES `translation_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`entry_id`) REFERENCES `translation_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`task_id`) REFERENCES `translation_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_proofread_suggestions_project_id` ON `translation_proofread_suggestions` (`project_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_proofread_suggestions_entry_id` ON `translation_proofread_suggestions` (`entry_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_proofread_suggestions_task_id` ON `translation_proofread_suggestions` (`task_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_proofread_suggestions_entry_id_created_at` ON `translation_proofread_suggestions` (`entry_id`, `created_at`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_review_issues` (
                        `id` TEXT NOT NULL,
                        `project_id` TEXT NOT NULL,
                        `entry_id` TEXT NOT NULL,
                        `task_id` TEXT,
                        `source` TEXT NOT NULL,
                        `code` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`project_id`) REFERENCES `translation_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`entry_id`) REFERENCES `translation_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`task_id`) REFERENCES `translation_tasks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_review_issues_project_id` ON `translation_review_issues` (`project_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_review_issues_entry_id` ON `translation_review_issues` (`entry_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_review_issues_task_id` ON `translation_review_issues` (`task_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_review_issues_project_id_state` ON `translation_review_issues` (`project_id`, `state`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_glossaries` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `source_project_id` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`source_project_id`) REFERENCES `translation_projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_translation_glossaries_source_project_id` ON `translation_glossaries` (`source_project_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_glossaries_updated_at` ON `translation_glossaries` (`updated_at`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translation_glossary_entries` (
                        `id` TEXT NOT NULL,
                        `glossary_id` TEXT NOT NULL,
                        `source_term` TEXT NOT NULL,
                        `target_term` TEXT NOT NULL,
                        `note` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`glossary_id`) REFERENCES `translation_glossaries`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_glossary_entries_glossary_id` ON `translation_glossary_entries` (`glossary_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_translation_glossary_entries_glossary_id_source_term` ON `translation_glossary_entries` (`glossary_id`, `source_term`)")
                db.execSQL(
                    """INSERT INTO translation_glossaries (
                        id, name, revision, source_project_id, created_at, updated_at
                    )
                    SELECT 'legacy-' || p.id, substr(p.title, 1, 80), p.glossary_revision, p.id, p.created_at, p.updated_at
                    FROM translation_projects p
                    WHERE EXISTS (
                        SELECT 1 FROM translation_glossary_terms t WHERE t.project_id = p.id
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO translation_glossary_entries (
                        id, glossary_id, source_term, target_term, note, updated_at
                    )
                    SELECT 'legacy-' || t.id, 'legacy-' || t.project_id, t.source_term,
                        t.target_term, t.note, t.updated_at
                    FROM translation_glossary_terms t""".trimIndent(),
                )
                db.execSQL(
                    "ALTER TABLE translation_projects ADD COLUMN selected_glossary_id TEXT " +
                        "REFERENCES translation_glossaries(id) ON DELETE SET NULL",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translation_projects_selected_glossary_id` " +
                        "ON `translation_projects` (`selected_glossary_id`)",
                )
                db.execSQL(
                    """UPDATE translation_projects SET selected_glossary_id =
                        (SELECT id FROM translation_glossaries WHERE source_project_id = translation_projects.id)
                        WHERE EXISTS (SELECT 1 FROM translation_glossaries
                            WHERE source_project_id = translation_projects.id)""".trimIndent(),
                )
            }
        }

        fun get(context: Context): TranslationWorkbenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TranslationWorkbenchDatabase::class.java,
                AppStorageNames.TRANSLATION_WORKBENCH_DATABASE,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
