package org.naruprof.twin.service.impl;

import org.naruprof.twin.infra.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counters and last-failure facts for private health/metrics, plus the structured diagnostic log
 * for repairs and conflicts. Only ids, revisions, durations and failure classes: never source
 * record content or credentials.
 */
public class ProjectionDiagnostics {

    private static final Logger log = LoggerFactory.getLogger("naruprof.twin.diagnostics");

    private final Clock clock;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastFailureClass = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final AtomicReference<U64> lastSourceSequence = new AtomicReference<>();
    private final AtomicReference<Instant> lastReconcileAt = new AtomicReference<>();

    public ProjectionDiagnostics(Clock clock) {
        this.clock = clock;
    }

    public void count(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public long counter(String name) {
        AtomicLong a = counters.get(name);
        return a == null ? 0 : a.get();
    }

    public Map<String, Long> counters() {
        Map<String, Long> m = new TreeMap<>();
        counters.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public void failure(String failureClass) {
        lastFailureClass.set(failureClass);
        lastFailureAt.set(clock.instant());
        count("failure." + failureClass);
    }

    public void clearFailure() {
        lastFailureClass.set(null);
    }

    /** Repairs/conflicts are always logged at WARN with a stable event name. */
    public void diagnostic(String event, String profile, String detail) {
        count("diagnostic." + event);
        log.warn("twin diagnostic event={} profile={} {}", event, profile, detail);
    }

    public void sourceSequence(U64 s) {
        lastSourceSequence.accumulateAndGet(s, (a, b) -> a == null || b.isGreaterThan(a) ? b : a);
    }

    public void resetSourceSequence(U64 s) {
        lastSourceSequence.set(s);
    }

    public void reconciled() {
        lastReconcileAt.set(clock.instant());
    }

    public String lastFailureClass() {
        return lastFailureClass.get();
    }

    public Instant lastFailureAt() {
        return lastFailureAt.get();
    }

    public U64 lastSourceSequence() {
        return lastSourceSequence.get();
    }

    public Instant lastReconcileAt() {
        return lastReconcileAt.get();
    }
}
