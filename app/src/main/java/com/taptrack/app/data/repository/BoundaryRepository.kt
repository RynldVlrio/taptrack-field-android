package com.taptrack.app.data.repository

import com.taptrack.app.data.local.dao.BoundaryDao
import com.taptrack.app.data.local.entity.BoundaryEntity
import kotlinx.coroutines.flow.Flow

class BoundaryRepository(private val dao: BoundaryDao) {
    fun getAll(): Flow<List<BoundaryEntity>> = dao.getAll()
    suspend fun insert(boundary: BoundaryEntity): Long = dao.insert(boundary)
    suspend fun update(boundary: BoundaryEntity) = dao.update(boundary)
    suspend fun delete(id: Long) = dao.deleteById(id)
}
