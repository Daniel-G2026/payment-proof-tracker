package com.danielanazodo.proofledger.data.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PreparedProofFile(
    val originalPath: String,
    val uploadPath: String?,
    val originalSizeBytes: Long,
    val uploadSizeBytes: Long?,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
)

@Singleton
class ProofFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun prepare(uri: Uri, id: String = UUID.randomUUID().toString()): PreparedProofFile {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val originalDir = File(context.filesDir, "proofs/originals").apply { mkdirs() }
        val originalFile = File(originalDir, "$id${extensionFor(mimeType)}")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open proof image." }
                originalFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            originalFile.delete()
            throw t
        }

        val bounds = decodeBounds(originalFile)
        val originalSize = originalFile.length()
        val uploadFile = if (originalSize > COMPRESS_THRESHOLD_BYTES) {
            createCompressedUploadCopy(originalFile, bounds, id)
        } else {
            null
        }
        val chosenUpload = uploadFile?.takeIf { it.length() in 1 until originalSize }
        if (uploadFile != null && uploadFile != chosenUpload) {
            uploadFile.delete()
        }

        return PreparedProofFile(
            originalPath = originalFile.absolutePath,
            uploadPath = chosenUpload?.absolutePath,
            originalSizeBytes = originalSize,
            uploadSizeBytes = chosenUpload?.length(),
            mimeType = mimeType,
            width = bounds.outWidth.takeIf { it > 0 },
            height = bounds.outHeight.takeIf { it > 0 },
        )
    }

    fun deleteForId(id: String) {
        val originalsDir = File(context.filesDir, "proofs/originals")
        val uploadsDir = File(context.filesDir, "proofs/uploads")
        originalsDir.listFiles { _, name -> name.startsWith("$id.") }?.forEach { it.delete() }
        uploadsDir.listFiles { _, name -> name.startsWith("$id.") }?.forEach { it.delete() }
    }

    private fun decodeBounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    private fun createCompressedUploadCopy(
        original: File,
        bounds: BitmapFactory.Options,
        id: String,
    ): File? {
        // Decode at scale instead of loading the full bitmap. A 12 MP photo
        // would otherwise allocate ~48 MB and OOM on low-end devices.
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_EDGE_PX)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(original.absolutePath, decodeOptions) ?: return null
        val scaled = scaleIfNeeded(bitmap)
        val uploadDir = File(context.filesDir, "proofs/uploads").apply { mkdirs() }
        val uploadFile = File(uploadDir, "$id.jpg")
        return try {
            uploadFile.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            uploadFile
        } catch (t: Throwable) {
            uploadFile.delete()
            throw t
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    private fun scaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE_PX) return bitmap
        val ratio = MAX_LONG_EDGE_PX.toFloat() / longEdge.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }

    private companion object {
        const val COMPRESS_THRESHOLD_BYTES = 500L * 1024L
        const val MAX_LONG_EDGE_PX = 1800
        const val JPEG_QUALITY = 85
    }
}
