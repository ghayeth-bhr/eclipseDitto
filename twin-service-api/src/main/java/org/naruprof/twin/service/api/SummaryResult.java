package org.naruprof.twin.service.api;

import org.naruprof.twin.infra.TwinSummaryView;

/** Outcome of a private summary read; each case maps to one HTTP status in the REST adapter. */
public sealed interface SummaryResult {

    /** 200: the actually applied revision (may lag the source). */
    record Found(TwinSummaryView view) implements SummaryResult {
    }

    /** 404 twin_missing: confirmed deletion (tombstone). */
    record Missing() implements SummaryResult {
    }

    /** 503 twin_unavailable: bootstrapping or infrastructure unavailable. */
    record Unavailable(String reason) implements SummaryResult {
    }

    /** 403: the path names a profile this installation does not serve. */
    record Forbidden() implements SummaryResult {
    }

    /** 400: not a canonical UUID. */
    record Invalid() implements SummaryResult {
    }
}
