package com.danielanazodo.proofledger.di

import android.content.Context
import androidx.room.Room
import com.danielanazodo.proofledger.data.local.AppDatabase
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
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_1_2
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_2_3
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_3_4
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_4_5
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_5_6
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_6_7
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_7_8
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_8_9
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_9_10
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_10_11
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_11_12
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_12_13
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_13_14
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
            )
            .build()

    @Provides
    fun provideLocalAppMetadataDao(db: AppDatabase): LocalAppMetadataDao =
        db.localAppMetadataDao()

    @Provides
    fun provideLocalProfileDao(db: AppDatabase): LocalProfileDao =
        db.localProfileDao()

    @Provides
    fun provideLocalBusinessDao(db: AppDatabase): LocalBusinessDao =
        db.localBusinessDao()

    @Provides
    fun provideLocalCategoryDao(db: AppDatabase): LocalCategoryDao =
        db.localCategoryDao()

    @Provides
    fun provideLocalCounterpartyDao(db: AppDatabase): LocalCounterpartyDao =
        db.localCounterpartyDao()

    @Provides
    fun provideLocalProjectDao(db: AppDatabase): LocalProjectDao =
        db.localProjectDao()

    @Provides
    fun provideLocalUnitDao(db: AppDatabase): LocalUnitDao =
        db.localUnitDao()

    @Provides
    fun provideLocalScreenshotDao(db: AppDatabase): LocalScreenshotDao =
        db.localScreenshotDao()

    @Provides
    fun provideLocalSyncEventDao(db: AppDatabase): LocalSyncEventDao =
        db.localSyncEventDao()

    @Provides
    fun provideLocalTransactionDao(db: AppDatabase): LocalTransactionDao =
        db.localTransactionDao()
}
