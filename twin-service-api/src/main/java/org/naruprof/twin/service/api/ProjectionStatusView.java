package org.naruprof.twin.service.api;

import java.util.Map;

/**
 * Private health/metrics view. Contains only ids, revisions, states and counters of operations,
 * never source record content or credentials.
 */
public record ProjectionStatusView(String profileId, String checkpointStatus, String appliedSequence,
                                   String lastSourceSequence, String replayLag, String consumerState,
                                   String lastFailureClass, String lastFailureAt, String circuitBreaker,
                                   String lastReconcileAt, Map<String, Long> counters) {
}
