package com.taptrack.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.taptrack.app.data.local.dao.BoundaryDao
import com.taptrack.app.data.local.entity.BoundaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Local copy of the server data (offline-first).
//   dirty   = changed on this phone, not yet uploaded
//   isNew   = created on this phone, never uploaded (uploaded with INSERT, later with PATCH)
//   syncError = last upload was refused by the server (shown to the user to fix)
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "barangays")
data class BarangayEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val code: String?,
    val deleted: Boolean = false,
)

@Entity(tableName = "tapstands")
data class TapstandEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val code: String?,               // null until verified (server assigns e.g. SN-TS-001)
    val barangayId: String?,
    val sitio: String?,
    val locationDesc: String?,
    val lat: Double,
    val lng: Double,
    val status: String,              // proposed / cancelled / surveyed / approved / …
    val maxConnections: Int = 10,
    val installedOn: String?,        // yyyy-MM-dd
    val remarks: String?,
    val isDraft: Boolean,            // true = proposed or cancelled (not official)
    val draftLabel: String?,
    val plannedLat: Double?,
    val plannedLng: Double?,
    val planSource: String?,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
    val dirty: Boolean = false,
    val isNew: Boolean = false,
    val makeOfficial: Boolean = false, // verify requested on this phone (code comes back after sync)
    val syncError: String? = null,
    val localUpdatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "applicants")
data class ApplicantEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val applicationNo: String?,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val suffix: String?,
    val contactNo: String?,
    val barangayId: String?,
    val sitio: String?,
    val address: String?,
    val householdSize: Int?,
    val accountType: String = "residential",
    val lat: Double?,
    val lng: Double?,
    val status: String = "submitted",
    val dateApplied: String,
    val assignedTapstandId: String?,
    val meterSlot: Int?,
    val meterSerial: String?,
    val decisionReason: String?,
    val remarks: String?,
    val deleted: Boolean = false,
    val syncVersion: Long = 0,
    val dirty: Boolean = false,
    val isNew: Boolean = false,
    val syncError: String? = null,
    val localUpdatedAt: Long = System.currentTimeMillis(),
)

/** Photo waiting to be uploaded to Supabase Storage (then recorded in `attachments`). */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val entityType: String,          // tapstand / applicant
    val entityId: String,
    val kind: String = "photo",
    val filePath: String,
    val takenAt: Long = System.currentTimeMillis(),
    val lat: Double?,
    val lng: Double?,
    val uploaded: Boolean = false,
    val error: String? = null,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(@PrimaryKey val key: String, val value: String)

// ── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface BarangayDao {
    @Query("SELECT * FROM barangays WHERE projectId = :projectId AND deleted = 0 ORDER BY name")
    fun observe(projectId: String): Flow<List<BarangayEntity>>

    @Upsert suspend fun upsertAll(rows: List<BarangayEntity>)
}

@Dao
interface TapstandDao {
    @Query("SELECT * FROM tapstands WHERE projectId = :projectId AND deleted = 0 ORDER BY isDraft, code, draftLabel")
    fun observe(projectId: String): Flow<List<TapstandEntity>>

    @Query("SELECT * FROM tapstands WHERE id = :id")
    suspend fun get(id: String): TapstandEntity?

    @Query("SELECT * FROM tapstands WHERE id = :id")
    fun observeOne(id: String): Flow<TapstandEntity?>

    @Query("SELECT * FROM tapstands WHERE dirty = 1")
    suspend fun dirty(): List<TapstandEntity>

    @Query("SELECT id FROM tapstands WHERE dirty = 1")
    suspend fun dirtyIds(): List<String>

    @Upsert suspend fun upsert(row: TapstandEntity)
    @Upsert suspend fun upsertAll(rows: List<TapstandEntity>)

    @Query("SELECT COUNT(*) FROM tapstands WHERE dirty = 1")
    fun observePending(): Flow<Int>
}

@Dao
interface ApplicantDao {
    @Query("SELECT * FROM applicants WHERE projectId = :projectId AND deleted = 0 ORDER BY localUpdatedAt DESC")
    fun observe(projectId: String): Flow<List<ApplicantEntity>>

    @Query("SELECT * FROM applicants WHERE id = :id")
    suspend fun get(id: String): ApplicantEntity?

    @Query("SELECT * FROM applicants WHERE dirty = 1")
    suspend fun dirty(): List<ApplicantEntity>

    @Query("SELECT id FROM applicants WHERE dirty = 1")
    suspend fun dirtyIds(): List<String>

    @Upsert suspend fun upsert(row: ApplicantEntity)
    @Upsert suspend fun upsertAll(rows: List<ApplicantEntity>)

    @Query("SELECT COUNT(*) FROM applicants WHERE dirty = 1")
    fun observePending(): Flow<Int>
}

@Dao
interface PhotoDao {
    @Insert suspend fun insert(row: PhotoEntity)

    @Query("SELECT * FROM photos WHERE uploaded = 0")
    suspend fun pending(): List<PhotoEntity>

    @Query("UPDATE photos SET uploaded = 1, error = NULL WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query("UPDATE photos SET error = :error WHERE id = :id")
    suspend fun markError(id: String, error: String?)

    @Query("SELECT * FROM photos WHERE entityType = :type AND entityId = :entityId ORDER BY takenAt")
    fun observeFor(type: String, entityId: String): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photos WHERE uploaded = 0")
    fun observePending(): Flow<Int>
}

@Dao
interface SyncMetaDao {
    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert suspend fun put(row: SyncMetaEntity)

    @Query("DELETE FROM sync_meta")
    suspend fun clear()
}

@Database(
    entities = [
        BarangayEntity::class, TapstandEntity::class, ApplicantEntity::class,
        PhotoEntity::class, SyncMetaEntity::class, BoundaryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LocalDb : RoomDatabase() {
    abstract fun barangays(): BarangayDao
    abstract fun tapstands(): TapstandDao
    abstract fun applicants(): ApplicantDao
    abstract fun photos(): PhotoDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun boundaryDao(): BoundaryDao

    /** Wipe synced data (sign-out / switch user). Map layers (boundaries) are kept. */
    suspend fun clearProjectData() = withContext(Dispatchers.IO) {
        val db = openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM barangays")
            db.execSQL("DELETE FROM tapstands")
            db.execSQL("DELETE FROM applicants")
            db.execSQL("DELETE FROM photos")
            db.execSQL("DELETE FROM sync_meta")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun create(context: Context): LocalDb =
            Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "taptrack_v6.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
