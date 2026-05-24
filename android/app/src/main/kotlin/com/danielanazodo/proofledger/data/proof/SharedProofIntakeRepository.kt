package com.danielanazodo.proofledger.data.proof

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SharedProofIntakeItem(
    val token: String,
    val localUri: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class SharedProofIntakeState(
    val pending: SharedProofIntakeItem? = null,
    val message: String? = null,
)

@Singleton
class SharedProofIntakeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(SharedProofIntakeState())
    val state: StateFlow<SharedProofIntakeState> = _state.asStateFlow()

    fun capture(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> captureSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> reject("Share one proof at a time.")
        }
    }

    fun get(token: String): SharedProofIntakeItem? =
        _state.value.pending?.takeIf { it.token == token && File(it.localPath).exists() }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun cancel(token: String) {
        clearToken(token)
    }

    fun complete(token: String) {
        clearToken(token)
    }

    private fun captureSingle(intent: Intent) {
        val streams = intent.streamUris()
        when {
            streams.isEmpty() -> reject("Could not read the shared proof image.")
            streams.size > 1 -> reject("Share one proof at a time.")
            else -> copyIntoIntake(streams.first(), intent.type)
        }
    }

    private fun copyIntoIntake(source: Uri, declaredMimeType: String?) {
        val mimeType = normalizedAllowedMimeType(context.contentResolver.getType(source))
            ?: normalizedAllowedMimeType(declaredMimeType)
            ?: return reject("Proof must be a JPEG, PNG, or WebP image.")
        val token = UUID.randomUUID().toString()
        val intakeDir = File(context.filesDir, "proofs/intake").apply { mkdirs() }
        val destination = File(intakeDir, "$token${extensionFor(mimeType)}")
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Could not read the shared proof image." }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            if (destination.length() <= 0L) {
                destination.delete()
                reject("Could not read the shared proof image.")
                return
            }
        } catch (t: Throwable) {
            destination.delete()
            reject(t.message ?: "Could not read the shared proof image.")
            return
        }

        _state.value.pending?.let { File(it.localPath).delete() }
        _state.value = SharedProofIntakeState(
            pending = SharedProofIntakeItem(
                token = token,
                localUri = destination.toURI().toString(),
                localPath = destination.absolutePath,
                mimeType = mimeType,
                sizeBytes = destination.length(),
            ),
            message = null,
        )
    }

    private fun reject(message: String) {
        _state.value = SharedProofIntakeState(message = message)
    }

    private fun clearToken(token: String) {
        val pending = _state.value.pending
        if (pending?.token == token) {
            File(pending.localPath).delete()
            _state.value = SharedProofIntakeState()
        }
    }

    private fun Intent.streamUris(): List<Uri> {
        val streams = buildList {
            streamExtra()?.let(::add)
            clipData?.let { clip ->
                repeat(clip.itemCount) { index ->
                    clip.getItemAt(index).uri?.let(::add)
                }
            }
        }
        return streams.distinctBy { it.toString() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun normalizedAllowedMimeType(value: String?): String? =
        when (value?.lowercase()) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> null
        }

    private fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
}
