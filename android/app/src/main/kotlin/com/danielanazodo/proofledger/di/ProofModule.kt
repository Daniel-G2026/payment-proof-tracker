package com.danielanazodo.proofledger.di

import com.danielanazodo.proofledger.data.export.AndroidExportFileWriter
import com.danielanazodo.proofledger.data.export.ExportFileWriter
import com.danielanazodo.proofledger.data.proof.AndroidProofFileStore
import com.danielanazodo.proofledger.data.proof.BackupWorkScheduler
import com.danielanazodo.proofledger.data.proof.ProofFileStore
import com.danielanazodo.proofledger.data.proof.WorkManagerBackupWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProofModule {
    @Binds
    @Singleton
    abstract fun bindProofFileStore(store: AndroidProofFileStore): ProofFileStore

    @Binds
    @Singleton
    abstract fun bindBackupWorkScheduler(scheduler: WorkManagerBackupWorkScheduler): BackupWorkScheduler

    @Binds
    @Singleton
    abstract fun bindExportFileWriter(writer: AndroidExportFileWriter): ExportFileWriter
}
