package com.danielanazodo.proofledger.di

import android.content.Context
import androidx.room.Room
import com.danielanazodo.proofledger.data.local.AppDatabase
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_1_2
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_2_3
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_3_4
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_4_5
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
    fun provideLocalCounterpartyDao(db: AppDatabase): LocalCounterpartyDao =
        db.localCounterpartyDao()

    @Provides
    fun provideLocalScreenshotDao(db: AppDatabase): LocalScreenshotDao =
        db.localScreenshotDao()

    @Provides
    fun provideLocalTransactionDao(db: AppDatabase): LocalTransactionDao =
        db.localTransactionDao()
}
