package org.naruprof.twin.service.api;

public record Readiness(boolean ready, boolean nativeReadAuthenticated, boolean projectionInitialized, String detail) {
}
