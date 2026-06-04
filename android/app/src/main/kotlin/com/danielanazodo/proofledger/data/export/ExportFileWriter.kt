package com.danielanazodo.proofledger.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes generated export files (CSV today, PDF later) and returns a shareable
 * `content://` URI. Infrastructure only — no Compose, no ViewModel. Callers run
 * it off the main thread. An interface so reporting logic stays unit-testable
 * without Android (mirrors ProofFileStore / AndroidProofFileStore).
 */
interface ExportFileWriter {
    fun writeText(fileName: String, content: String): Uri

    /** Write raw bytes (e.g. a server-generated PDF) and return a shareable URI. */
    fun writeBytes(fileName: String, bytes: ByteArray): Uri
}

/**
 * Files land in `cacheDir/exports`, which the FileProvider `<cache-path>` in
 * the manifest exposes. Cache means the OS can reclaim them; that's fine —
 * an export is a transient artifact the user shares immediately.
 */
@Singleton
class AndroidExportFileWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportFileWriter {
    override fun writeText(fileName: String, content: String): Uri =
        writeFile(fileName) { it.writeText(content, Charsets.UTF_8) }

    override fun writeBytes(fileName: String, bytes: ByteArray): Uri =
        writeFile(fileName) { it.writeBytes(bytes) }

    private inline fun writeFile(fileName: String, write: (File) -> Unit): Uri {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // Best-effort prune of stale exports so the cache dir doesn't grow
        // unbounded across many shares. The current file is rewritten below.
        dir.listFiles()?.forEach { existing ->
            if (existing.name != fileName) existing.delete()
        }
        val file = File(dir, fileName)
        write(file)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val EXPORT_DIR = "exports"
    }
}
