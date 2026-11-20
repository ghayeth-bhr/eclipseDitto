package org.naruprof.twin.dal.api;

/** Result of a conditional native write. Transport failures are exceptions, not outcomes. */
public enum WriteOutcome {
    /** Entity created (201). */
    CREATED,
    /** Entity modified/deleted (2xx). */
    APPLIED,
    /** 412: the If-Match / If-None-Match precondition failed; reread and decide again. */
    PRECONDITION_FAILED,
    /** 404: not visible/absent. */
    NOT_FOUND
}
