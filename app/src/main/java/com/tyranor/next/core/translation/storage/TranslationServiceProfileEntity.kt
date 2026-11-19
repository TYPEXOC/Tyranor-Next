package com.tyranor.next.core.translation.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Service metadata only. API secrets live in TranslationSecretStore, never in Room. */
@Entity(
    tableName = "translation_service_profiles",
    indices = [Index(value = ["updated_at"])],
)
internal data class TranslationServiceProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    val enabled: Boolean,
    val revision: Long,
    @ColumnInfo(name = "translation_prompt") val translationPrompt: String,
    @ColumnInfo(name = "proofreading_prompt", defaultValue = "''") val proofreadingPrompt: String = "",
    @ColumnInfo(name = "translation_user_prompt", defaultValue = "''") val translationUserPrompt: String = "",
    @ColumnInfo(name = "proofreading_user_prompt", defaultValue = "''") val proofreadingUserPrompt: String = "",
    @ColumnInfo(name = "generation_options_json", defaultValue = "'{}'") val generationOptionsJson: String = "{}",
    @ColumnInfo(name = "secret_ref") val secretRef: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
