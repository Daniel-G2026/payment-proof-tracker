package com.danielanazodo.proofledger.di

import android.content.Context
import androidx.room.Room
import com.danielanazodo.proofledger.data.local.AppDatabase
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalBusinessDao
import com.danielanazodo.proofledger.data.local.dao.LocalProfileDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_1_2
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_2_3
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    fun provideLocalTransactionDao(db: AppDatabase): LocalTransactionDao =
        db.localTransactionDao()
}
