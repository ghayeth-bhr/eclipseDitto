package org.naruprof.twin.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All settings come from protected launcher configuration (environment). Never logged. */
@ConfigurationProperties(prefix = "twin")
public record TwinProperties(String profileId, String dataDir, Inbound inbound, Naru naru, Ditto ditto, Projection projection) {

    public record Inbound(String vertxToken, String maintenanceToken) {
    }

    public record Naru(String mode, String target, String token, Duration snapshotTimeout, Duration watchMaxDuration,
                       Duration keepAliveTime) {
    }

    public record Ditto(String apiBase, String subjectIssuer, Duration connectTimeout, Duration requestTimeout,
                        Duration readTimeout, Credential operator, Credential projector, Credential reader, Breaker breaker) {
    }

    public record Credential(String user, String password) {
    }

    public record Breaker(float failureRateThreshold, int slidingWindowSize, int minimumCalls, Duration openDuration) {
    }

    public record Projection(String writerId, int maxWriteAttempts, int queueCapacity, Duration backoffBase,
                             Duration backoffCap, Duration actionableFailureDelay, Duration leaseTtl,
                             Duration reconcileInterval, Duration drainTimeout) {
    }
}
