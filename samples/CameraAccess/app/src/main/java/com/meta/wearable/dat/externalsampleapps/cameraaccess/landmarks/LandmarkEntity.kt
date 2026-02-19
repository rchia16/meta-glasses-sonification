package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "landmarks",
    indices = [Index(value = ["name_normalized"], unique = true)],
)
data class LandmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "name_normalized")
    val nameNormalized: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: Float?,
)

