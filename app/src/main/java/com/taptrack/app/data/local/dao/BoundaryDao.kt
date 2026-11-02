package com.taptrack.app.data.local.dao

import androidx.room.*
import com.taptrack.app.data.local.entity.BoundaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BoundaryDao {
    @Query("SELECT * FROM boundaries ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BoundaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(boundary: BoundaryEntity): Long

    @Update
    suspend fun update(boundary: BoundaryEntity)

    @Query("DELETE FROM boundaries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
