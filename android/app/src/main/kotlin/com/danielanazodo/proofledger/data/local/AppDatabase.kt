package com.danielanazodo.proofledger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity

@Database(
    entities = [LocalAppMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localAppMetadataDao(): LocalAppMetadataDao

    companion object {
        const val NAME = "proofledger.db"
    }
}
