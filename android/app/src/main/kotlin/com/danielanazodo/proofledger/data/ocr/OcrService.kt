package com.danielanazodo.proofledger.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps Google ML Kit on-device Latin text recognition. Runs entirely on the
 * device — no network, no images leave the phone. Returns null when OCR
 * fails or finds nothing; callers should treat that as "no draft, leave the
 * form blank" rather than surfacing an error.
 */
@Singleton
class OcrService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Run OCR on a local image URI. Returns the raw text, or null on failure. */
    suspend fun extractText(uri: Uri): String? = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        recognize(image)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
