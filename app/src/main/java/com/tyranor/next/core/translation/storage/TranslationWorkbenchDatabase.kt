package com.tyranor.next.core.translation.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tyranor.next.core.settings.AppStorageNames

@Database(
    entities = [TranslationProjectEntity::class, TranslationEntryEntity::class, TranslationTaskEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class TranslationWorkbenchDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationWorkbenchDao

    companion object {
        @Volatile
        private var instance: TranslationWorkbenchDatabase? = null

        fun get(context: Context): TranslationWorkbenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TranslationWorkbenchDatabase::class.java,
                AppStorageNames.TRANSLATION_WORKBENCH_DATABASE,
            )
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
