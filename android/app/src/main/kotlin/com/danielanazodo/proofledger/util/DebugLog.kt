package com.danielanazodo.proofledger.util

import android.util.Log
import com.danielanazodo.proofledger.BuildConfig

/**
 * Thin wrapper around [android.util.Log] that short-circuits in release builds.
 *
 * Auth tokens, session expirations, profile IDs, OTP submissions, network URLs
 * — all of these are useful when chasing a bug locally but should never appear
 * in logcat on a user's device. Gating every call site behind BuildConfig.DEBUG
 * by hand is noisy and easy to forget; routing through these helpers makes the
 * gate a single line of code that R8 can also dead-strip in release.
 *
 * Use [w] / [e] for the small set of failures we still want visible in
 * production (uncaught exceptions, crash-adjacent failures). Those are
 * sanitized at the call site rather than gated here.
 */
object DebugLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    inline fun w(tag: String, message: () -> String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message(), throwable)
    }

    /** Errors are emitted in release too — but the lambda still keeps potentially
     *  sensitive string-building out of the hot path until we know we'll log. */
    inline fun e(tag: String, message: () -> String, throwable: Throwable? = null) {
        Log.e(tag, message(), throwable)
    }
}
