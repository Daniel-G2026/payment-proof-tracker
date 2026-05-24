package com.danielanazodo.proofledger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity

@Database(
    entities = [
        LocalAppMetadataEntity::class,
        LocalProfileEntity::class,
        LocalBusinessEntity::class,
        LocalTransactionEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(SyncStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localAppMetadataDao(): LocalAppMetadataDao
    abstract fun localProfileDao(): LocalProfileDao
    abstract fun localBusinessDao(): LocalBusinessDao
    abstract fun localTransactionDao(): LocalTransactionDao

    companion object {
        const val NAME = "proofledger.db"
    }
}
