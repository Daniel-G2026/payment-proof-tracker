"""LIKE / ILIKE wildcard escaping for user-supplied search terms.

Without escaping, a search for ``50%`` or ``a_b`` treats ``%`` and ``_`` as SQL
wildcards, silently widening the match (``50%`` matches everything starting with
"50"). Callers must build the pattern from the escaped term **and** pass
``escape="\\"`` to ``.like()`` / ``.ilike()`` so the backslash added here is
honored by the database.
"""

from __future__ import annotations

# The escape character we tell SQL to use (``escape="\\"`` at the call site).
LIKE_ESCAPE_CHAR = "\\"


def escape_like(term: str) -> str:
    """Escape ``\\``, ``%`` and ``_`` so the term matches literally.

    Order matters: the escape char itself must be doubled first, otherwise the
    backslashes we add for ``%``/``_`` would get double-escaped.
    """
    return (
        term.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    )
