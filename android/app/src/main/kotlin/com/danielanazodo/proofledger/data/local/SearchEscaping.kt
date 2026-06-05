package com.danielanazodo.proofledger.data.local

/**
 * Escapes SQL `LIKE` wildcards (`%` and `_`) plus the escape character itself so
 * a user's search term is matched literally instead of as a wildcard pattern.
 *
 * Without this, searching for `50%` or `a_b` would treat `%`/`_` as wildcards
 * and silently widen the match. Every DAO `LIKE` clause that consumes a
 * pre-escaped term MUST declare `ESCAPE '\'` so the backslash added here is
 * honored.
 *
 * Order matters: the escape char is doubled first, otherwise the backslashes
 * added for `%`/`_` would themselves get escaped.
 */
fun escapeLikeTerm(term: String): String =
    term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
