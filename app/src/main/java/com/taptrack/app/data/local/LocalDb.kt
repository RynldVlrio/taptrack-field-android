package com.taptrack.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val sourceLeadUuid: String? = null,   // HH Survey lead (record_uuid) this application started from
)

/**
 * HH Survey lead (household survey, uploaded as CSV on the web). View-only on phones.
 * Replaced in full on sync (a CSV re-upload deletes and re-inserts every lead).
 * Income range is deliberately not downloaded.
 */
@Entity(tableName = "leads")
data class LeadEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val recordUuid: String?,         // survey record ID — stable across CSV re-uploads (applicants link to this)
    val surveyDate: String?,
    val barangayId: String?,
    val barangayName: String?,
    val zone: String?,
    val firstName: String,
    val lastName: String,
    val contactNo: String?,
    val householdMembers: Int?,
    val householdOwnership: String?,
    val existingWaterSource: String?,
    val drinkingWaterSource: String?,
    val willingness: String?,
    val remarks: String?,
    val lat: Double?,
    val lng: Double?,
    val mapsLink: String?,
    val photoLink: String?,
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

/** Shared map layer from the web (GeoJSON downloaded for offline use). Read-only on phones except the on/off switch. */
@Entity(tableName = "map_layers")
data class MapLayerEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val storagePath: String,
    val fileType: String,
    val strokeColor: Int,            // ARGB
    val fillColor: Int,              // ARGB (alpha = fill opacity)
    val weight: Float,
    val showLabel: Boolean,
    val defaultVisible: Boolean,
    val deleted: Boolean,
    val syncVersion: Long,
    val updatedAt: String,
    val localFile: String? = null,   // downloaded GeoJSON on this phone
    val localVersion: String? = null, // updatedAt of the downloaded copy
    val userVisible: Boolean? = null, // this phone's on/off choice (null = layer default)
    @androidx.room.ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,           // order set on the web: lower = drawn on top
)

/** Shown on this phone? (its own on/off choice, else the office default) */
val MapLayerEntity.visible: Boolean get() = userVisible ?: defaultVisible

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

    /** Created on this phone and not uploaded yet (not on the server). */
    @Query("SELECT id FROM tapstands WHERE isNew = 1")
    suspend fun newIds(): List<String>

    @Upsert suspend fun upsert(row: TapstandEntity)
    @Upsert suspend fun upsertAll(rows: List<TapstandEntity>)

    @Query("SELECT COUNT(*) FROM tapstands WHERE dirty = 1")
    fun observePending(): Flow<Int>

    /** Rows that came from the server and have no local changes (safe to drop if the server no longer has them). */
    @Query("SELECT id FROM tapstands WHERE projectId = :projectId AND dirty = 0 AND isNew = 0")
    suspend fun syncedIds(projectId: String): List<String>

    @Query("DELETE FROM tapstands WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
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

    @Query("SELECT id FROM applicants WHERE isNew = 1")
    suspend fun newIds(): List<String>

    @Upsert suspend fun upsert(row: ApplicantEntity)
    @Upsert suspend fun upsertAll(rows: List<ApplicantEntity>)

    @Query("SELECT COUNT(*) FROM applicants WHERE dirty = 1")
    fun observePending(): Flow<Int>

    @Query("SELECT id FROM applicants WHERE projectId = :projectId AND dirty = 0 AND isNew = 0")
    suspend fun syncedIds(projectId: String): List<String>

    @Query("DELETE FROM applicants WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
}

@Dao
abstract class LeadDao {
    @Query("SELECT * FROM leads WHERE projectId = :projectId ORDER BY barangayName, lastName, firstName")
    abstract fun observe(projectId: String): Flow<List<LeadEntity>>

    @Query("SELECT * FROM leads WHERE id = :id")
    abstract suspend fun get(id: String): LeadEntity?

    @Query("SELECT * FROM leads WHERE recordUuid = :uuid LIMIT 1")
    abstract suspend fun getByUuid(uuid: String): LeadEntity?

    @Query("SELECT COUNT(*) FROM leads WHERE projectId = :projectId")
    abstract fun observeCount(projectId: String): Flow<Int>

    @Query("DELETE FROM leads WHERE projectId = :projectId")
    protected abstract suspend fun deleteProject(projectId: String)

    @Upsert protected abstract suspend fun upsertAll(rows: List<LeadEntity>)

    /** Swap in the server's full list in one step (the list never shows half-loaded). */
    @Transaction
    open suspend fun replaceAll(projectId: String, rows: List<LeadEntity>) {
        deleteProject(projectId)
        upsertAll(rows)
    }
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

    /** Surveyed → Installed: the installation photo replaces the earlier photos on this phone too. */
    @Query("DELETE FROM photos WHERE entityType = :type AND entityId = :entityId AND kind != 'installation'")
    suspend fun deleteNonInstallation(type: String, entityId: String)
}

@Dao
interface MapLayerDao {
    @Query("SELECT * FROM map_layers WHERE projectId = :projectId AND deleted = 0 ORDER BY sortOrder, name")
    fun observe(projectId: String): Flow<List<MapLayerEntity>>

    @Query("SELECT * FROM map_layers")
    suspend fun all(): List<MapLayerEntity>

    @Upsert suspend fun upsertAll(rows: List<MapLayerEntity>)

    @Query("UPDATE map_layers SET localFile = :file, localVersion = :version WHERE id = :id")
    suspend fun markDownloaded(id: String, file: String?, version: String?)

    @Query("UPDATE map_layers SET userVisible = :visible WHERE id = :id")
    suspend fun setUserVisible(id: String, visible: Boolean?)
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
        PhotoEntity::class, SyncMetaEntity::class, BoundaryEntity::class, MapLayerEntity::class,
        LeadEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class LocalDb : RoomDatabase() {
    abstract fun barangays(): BarangayDao
    abstract fun tapstands(): TapstandDao
    abstract fun applicants(): ApplicantDao
    abstract fun photos(): PhotoDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun boundaryDao(): BoundaryDao
    abstract fun mapLayers(): MapLayerDao
    abstract fun leads(): LeadDao

    /** Wipe synced data (sign-out / switch user). Map layers (boundaries) are kept. */
    suspend fun clearProjectData() = withContext(Dispatchers.IO) {
        val db = openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM barangays")
            db.execSQL("DELETE FROM tapstands")
            db.execSQL("DELETE FROM applicants")
            db.execSQL("DELETE FROM leads")
            db.execSQL("DELETE FROM photos")
            db.execSQL("DELETE FROM map_layers")
            db.execSQL("DELETE FROM sync_meta")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        /** v1 → v2: shared map layers. Keeps unsynced field data. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `map_layers` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`storagePath` TEXT NOT NULL, `fileType` TEXT NOT NULL, `strokeColor` INTEGER NOT NULL, `fillColor` INTEGER NOT NULL, " +
                        "`weight` REAL NOT NULL, `showLabel` INTEGER NOT NULL, `defaultVisible` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                        "`syncVersion` INTEGER NOT NULL, `updatedAt` TEXT NOT NULL, `localFile` TEXT, `localVersion` TEXT, `userVisible` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v2 → v3: layer order from the web; re-pull layers so every row gets its order. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `map_layers` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM sync_meta WHERE `key` = 'v_map_layers'")
            }
        }

        /** v3 → v4: HH Survey leads + applicant link to a lead. Keeps unsynced field data. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `applicants` ADD COLUMN `sourceLeadUuid` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leads` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `recordUuid` TEXT, " +
                        "`surveyDate` TEXT, `barangayId` TEXT, `barangayName` TEXT, `zone` TEXT, `firstName` TEXT NOT NULL, " +
                        "`lastName` TEXT NOT NULL, `contactNo` TEXT, `householdMembers` INTEGER, `householdOwnership` TEXT, " +
                        "`existingWaterSource` TEXT, `drinkingWaterSource` TEXT, `willingness` TEXT, `remarks` TEXT, " +
                        "`lat` REAL, `lng` REAL, `mapsLink` TEXT, `photoLink` TEXT, PRIMARY KEY(`id`))"
                )
                // re-pull applicants so existing rows get their lead link
                db.execSQL("DELETE FROM sync_meta WHERE `key` = 'v_applicants'")
            }
        }

        fun create(context: Context): LocalDb =
            Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "taptrack_v6.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}
