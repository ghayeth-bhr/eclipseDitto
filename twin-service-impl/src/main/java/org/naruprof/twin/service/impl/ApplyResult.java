package org.naruprof.twin.service.impl;

public enum ApplyResult {
    /** New state written and confirmed. */
    APPLIED,
    /** Native state already equal to the target; checkpoint confirmed. */
    ALREADY_APPLIED,
    /** A lower replay revision: ignored, the twin is never rewound. */
    IGNORED_STALE,
    /** Equal sequence with different content (or malformed owned state): reconcile with a fresh snapshot. */
    DRIFT,
    /** Equal-version state corrected from an authoritative snapshot. */
    REPAIRED,
    /** Event for a retired profile id (including delayed creates): rejected. */
    RETIRED_IGNORED,
    /** Profile is in RESTORE_CONFLICT: no writes until explicit reconciliation. */
    CONFLICT_PENDING
}
