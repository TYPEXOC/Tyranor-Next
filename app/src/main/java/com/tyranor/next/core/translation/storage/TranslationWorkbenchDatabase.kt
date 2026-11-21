package com.tyranor.next.core.translation.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tyranor.next.core.settings.AppStorageNames
import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.defaultBaseUrl

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
        TranslationReviewIssueEntity::class,
    ],
    version = 13,
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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN translation_user_prompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN generation_options_json TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedDefaultWebTranslationProfiles(db)
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN task_settings_json TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN custom_parameters_ref TEXT")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_service_profiles ADD COLUMN template_id TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TEMP TABLE translation_review_issues_backup (
                        id TEXT NOT NULL,
                        project_id TEXT NOT NULL,
                        entry_id TEXT NOT NULL,
                        task_id TEXT,
                        source TEXT NOT NULL,
                        code TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        state TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO translation_review_issues_backup
                        SELECT id, project_id, entry_id, task_id, source, code, severity, state, updated_at
                        FROM translation_review_issues WHERE source != 'AI'""".trimIndent(),
                )
                db.execSQL("DROP TABLE translation_review_issues")
                db.execSQL("DROP TABLE IF EXISTS translation_proofread_suggestions")
                db.execSQL("DELETE FROM translation_tasks WHERE type = 'AI_PROOFREAD'")
                db.execSQL(
                    "UPDATE translation_entries SET review_state = 'UNREVIEWED' " +
                        "WHERE review_state IN ('AI_REVIEWED', 'PROOFREAD_FAILED')",
                )
                db.execSQL(
                    """CREATE TABLE translation_entries_new (
                        id TEXT NOT NULL,
                        project_id TEXT NOT NULL,
                        source_key TEXT NOT NULL,
                        source_text TEXT NOT NULL,
                        translated_text TEXT,
                        source_file TEXT,
                        source_pointer TEXT,
                        text_type TEXT,
                        status TEXT NOT NULL,
                        failure_code TEXT,
                        review_state TEXT NOT NULL DEFAULT 'UNREVIEWED',
                        PRIMARY KEY(id),
                        FOREIGN KEY(project_id) REFERENCES translation_projects(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO translation_entries_new (
                        id, project_id, source_key, source_text, translated_text, source_file,
                        source_pointer, text_type, status, failure_code, review_state
                    ) SELECT id, project_id, source_key, source_text, translated_text, source_file,
                        source_pointer, text_type, status, failure_code, review_state
                        FROM translation_entries""".trimIndent(),
                )
                db.execSQL("DROP TABLE translation_entries")
                db.execSQL("ALTER TABLE translation_entries_new RENAME TO translation_entries")
                db.execSQL("CREATE INDEX index_translation_entries_project_id ON translation_entries(project_id)")
                db.execSQL(
                    "CREATE UNIQUE INDEX index_translation_entries_project_id_source_key " +
                        "ON translation_entries(project_id, source_key)",
                )
                db.execSQL(
                    """CREATE TABLE translation_review_issues (
                        id TEXT NOT NULL,
                        project_id TEXT NOT NULL,
                        entry_id TEXT NOT NULL,
                        task_id TEXT,
                        source TEXT NOT NULL,
                        code TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        state TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(project_id) REFERENCES translation_projects(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(entry_id) REFERENCES translation_entries(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(task_id) REFERENCES translation_tasks(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO translation_review_issues
                        SELECT id, project_id, entry_id, task_id, source, code, severity, state, updated_at
                        FROM translation_review_issues_backup""".trimIndent(),
                )
                db.execSQL("DROP TABLE translation_review_issues_backup")
                db.execSQL("CREATE INDEX index_translation_review_issues_project_id ON translation_review_issues(project_id)")
                db.execSQL("CREATE INDEX index_translation_review_issues_entry_id ON translation_review_issues(entry_id)")
                db.execSQL("CREATE INDEX index_translation_review_issues_task_id ON translation_review_issues(task_id)")
                db.execSQL(
                    "CREATE INDEX index_translation_review_issues_project_id_state " +
                        "ON translation_review_issues(project_id, state)",
                )

                db.execSQL(
                    """CREATE TABLE translation_service_profiles_new (
                        id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        protocol_id TEXT NOT NULL,
                        base_url TEXT NOT NULL,
                        template_id TEXT,
                        model_id TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        translation_prompt TEXT NOT NULL,
                        translation_user_prompt TEXT NOT NULL DEFAULT '',
                        generation_options_json TEXT NOT NULL DEFAULT '{}',
                        task_settings_json TEXT NOT NULL DEFAULT '{}',
                        secret_ref TEXT,
                        custom_parameters_ref TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO translation_service_profiles_new (
                        id, display_name, protocol_id, base_url, template_id, model_id, enabled, revision,
                        translation_prompt, translation_user_prompt, generation_options_json,
                        task_settings_json, secret_ref, custom_parameters_ref, created_at, updated_at
                    ) SELECT id, display_name, protocol_id, base_url, template_id, model_id, enabled, revision,
                        translation_prompt, translation_user_prompt, generation_options_json,
                        task_settings_json, secret_ref, custom_parameters_ref, created_at, updated_at
                        FROM translation_service_profiles""".trimIndent(),
                )
                db.execSQL("DROP TABLE translation_service_profiles")
                db.execSQL("ALTER TABLE translation_service_profiles_new RENAME TO translation_service_profiles")
                db.execSQL(
                    "CREATE INDEX index_translation_service_profiles_updated_at " +
                        "ON translation_service_profiles(updated_at)",
                )
            }
        }

        private val SEED_DEFAULT_MACHINE_PROFILES = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedDefaultWebTranslationProfiles(db)
            }
        }

        private fun seedDefaultWebTranslationProfiles(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            listOf(
                TranslationProtocolId.BING_TRANSLATOR_WEB to "Bing Translator",
                TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1 to "Youdao Translator",
                TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1 to "Google Translate",
            ).forEach { (protocol, displayName) ->
                val exists = db.query(
                    "SELECT 1 FROM translation_service_profiles WHERE protocol_id = ? LIMIT 1",
                    arrayOf(protocol.wireId),
                ).use { it.moveToFirst() }
                if (!exists) {
                    db.execSQL(
                        """INSERT INTO translation_service_profiles (
                            id, display_name, protocol_id, base_url, model_id, enabled, revision,
                            translation_prompt, translation_user_prompt, generation_options_json,
                            secret_ref, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, '', 1, 1, ?, '', '{}', NULL, ?, ?)""".trimIndent(),
                        arrayOf<Any?>(
                            "builtin-machine-${protocol.wireId}",
                            displayName,
                            protocol.wireId,
                            protocol.defaultBaseUrl(),
                            DEFAULT_TRANSLATION_PROMPT,
                            now,
                            now,
                        ),
                    )
                }
            }
        }

        fun get(context: Context): TranslationWorkbenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TranslationWorkbenchDatabase::class.java,
                AppStorageNames.TRANSLATION_WORKBENCH_DATABASE,
            )
                .addCallback(SEED_DEFAULT_MACHINE_PROFILES)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                )
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
