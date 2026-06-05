package com.danielanazodo.proofledger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.dao.LocalCategoryDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.dao.LocalProjectDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalSyncEventDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.dao.LocalUnitDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity

@Database(
    entities = [
        LocalAppMetadataEntity::class,
        LocalProfileEntity::class,
        LocalBusinessEntity::class,
        LocalCategoryEntity::class,
        LocalCounterpartyEntity::class,
        LocalProjectEntity::class,
        LocalUnitEntity::class,
        LocalScreenshotEntity::class,
        LocalSyncEventEntity::class,
        LocalTransactionEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
@TypeConverters(SyncStatusConverter::class, ProofStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localAppMetadataDao(): LocalAppMetadataDao
    abstract fun localProfileDao(): LocalProfileDao
    abstract fun localBusinessDao(): LocalBusinessDao
    abstract fun localCategoryDao(): LocalCategoryDao
    abstract fun localCounterpartyDao(): LocalCounterpartyDao
    abstract fun localProjectDao(): LocalProjectDao
    abstract fun localUnitDao(): LocalUnitDao
    abstract fun localScreenshotDao(): LocalScreenshotDao
    abstract fun localSyncEventDao(): LocalSyncEventDao
    abstract fun localTransactionDao(): LocalTransactionDao

    companion object {
        const val NAME = "proofledger.db"
    }
}
