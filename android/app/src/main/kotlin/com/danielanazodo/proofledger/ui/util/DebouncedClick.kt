package com.danielanazodo.proofledger.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.os.SystemClock

/**
 * Wraps an onClick lambda so rapid repeated taps within [windowMs] are ignored.
 *
 * Useful for list-item taps and navigation buttons where a double-tap could
 * cause two navigation events to fire — pushing two screens onto the back
 * stack, or worse, navigating into whatever item the second tap happened to
 * land on after the list shifted.
 *
 * 300 ms is the standard "double-tap" window — long enough to absorb finger
 * bounce and accidental repeats, short enough that deliberate fast tapping
 * (different buttons in quick succession) still feels responsive.
 *
 * Uses SystemClock.uptimeMillis() so the clock isn't affected by user time
 * changes. State is remembered per call site, so debounce windows on
 * different buttons don't interfere with each other.
 */
@Composable
fun rememberDebouncedClick(
    windowMs: Long = 300L,
    onClick: () -> Unit,
): () -> Unit {
    val lastFiredAt = remember { longArrayOf(0L) }
    return remember(onClick, windowMs) {
        {
            val now = SystemClock.uptimeMillis()
            if (now - lastFiredAt[0] >= windowMs) {
                lastFiredAt[0] = now
                onClick()
            }
        }
    }
}

/**
 * Variant for click handlers that take an argument (typical for list-item
 * taps: `onItemClick(id)`). Same semantics as [rememberDebouncedClick].
 */
@Composable
fun <T> rememberDebouncedClick(
    windowMs: Long = 300L,
    onClick: (T) -> Unit,
): (T) -> Unit {
    val lastFiredAt = remember { longArrayOf(0L) }
    return remember(onClick, windowMs) {
        { arg ->
            val now = SystemClock.uptimeMillis()
            if (now - lastFiredAt[0] >= windowMs) {
                lastFiredAt[0] = now
                onClick(arg)
            }
        }
    }
}
