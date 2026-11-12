package com.rohitkhandel.ggequalizer.presets

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY isBuiltIn DESC, presetName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE presetId = :presetId LIMIT 1")
    suspend fun findById(presetId: String): PresetEntity?

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(presets: List<PresetEntity>)

    @Delete
    suspend fun delete(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE presetId = :presetId AND isBuiltIn = 0")
    suspend fun deleteCustomById(presetId: String): Int

    @Query("DELETE FROM presets WHERE isBuiltIn = 0")
    suspend fun deleteAllCustom(): Int
}
