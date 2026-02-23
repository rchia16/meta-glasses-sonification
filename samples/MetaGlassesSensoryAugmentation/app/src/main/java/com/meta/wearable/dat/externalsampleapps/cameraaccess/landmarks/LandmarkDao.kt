package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmSuppressWildcards

@Dao
@JvmSuppressWildcards
interface LandmarkDao {
    @Query("SELECT * FROM landmarks ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<LandmarkEntity>>

    @Query("SELECT * FROM landmarks WHERE name_normalized = :nameNormalized LIMIT 1")
    suspend fun findByNormalizedName(nameNormalized: String): LandmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(landmark: LandmarkEntity): Long

    @Query("DELETE FROM landmarks WHERE name_normalized = :nameNormalized")
    suspend fun deleteByNormalizedName(nameNormalized: String): Int
}


