package com.danielanazodo.proofledger.di

import android.content.Context
import androidx.room.Room
import com.danielanazodo.proofledger.data.local.AppDatabase
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
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
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideLocalAppMetadataDao(db: AppDatabase): LocalAppMetadataDao =
        db.localAppMetadataDao()
}
