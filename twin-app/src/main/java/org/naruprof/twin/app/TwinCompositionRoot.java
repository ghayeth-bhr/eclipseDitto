package org.naruprof.twin.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.dal.api.TwinPolicyStore;
import org.naruprof.twin.dal.api.TwinReadModel;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinThingStore;
import org.naruprof.twin.dal.ditto.DittoHttpTransport;
import org.naruprof.twin.dal.ditto.DittoIdentity;
import org.naruprof.twin.dal.ditto.DittoPolicyStore;
import org.naruprof.twin.dal.ditto.DittoReadModel;
import org.naruprof.twin.dal.ditto.DittoThingStore;
import org.naruprof.twin.dal.jpa.repo.JpaCheckpointStore;
import org.naruprof.twin.dal.jpa.repo.JpaLeaseStore;
import org.naruprof.twin.dal.jpa.repo.SpringDataCheckpointRepository;
import org.naruprof.twin.dal.jpa.repo.SpringDataLeaseRepository;
import org.naruprof.twin.dal.narugrpc.BearerCallCredentials;
import org.naruprof.twin.dal.narugrpc.GrpcLearningSource;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.service.api.LearningTwinModule;
import org.naruprof.twin.service.impl.DeletionLifecycle;
import org.naruprof.twin.service.impl.LearningTwinModuleImpl;
import org.naruprof.twin.service.impl.ProfileProjector;
import org.naruprof.twin.service.impl.ProjectionDiagnostics;
import org.naruprof.twin.service.impl.ProjectionService;
import org.naruprof.twin.service.impl.ProjectionSettings;
import org.naruprof.twin.service.impl.ProvisioningService;
import org.naruprof.twin.service.impl.ReconcileService;
import org.naruprof.twin.service.impl.SummaryReadService;
import org.naruprof.twin.service.impl.TwinPolicyTemplate;
import org.naruprof.twin.ws.rest.ServiceTokenFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Composition root: the ONLY place where vendor implementations (Ditto HTTP, JPA, gRPC) are
 * bound to the semantic ports and the services are assembled.
 */
@Configuration(proxyBeanMethods = false)
public class TwinCompositionRoot {

    private static final Logger log = LoggerFactory.getLogger(TwinCompositionRoot.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProfileId configuredProfile(TwinProperties p) {
        if (p.profileId() == null || p.profileId().isBlank()) {
            throw new IllegalStateException("NARUPROF_PROFILE_ID must be configured");
        }
        return ProfileId.parse(p.profileId().trim());
    }

    @Bean
    ProjectionDiagnostics projectionDiagnostics(Clock clock) {
        return new ProjectionDiagnostics(clock);
    }

    // ------------------------------------------------------------------ Ditto (HTTP)

    @Bean
    CircuitBreaker dittoCircuitBreaker(TwinProperties p) {
        TwinProperties.Breaker b = p.ditto().breaker();
        return CircuitBreaker.of("ditto", CircuitBreakerConfig.custom()
                .failureRateThreshold(b.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(b.slidingWindowSize())
                .minimumNumberOfCalls(b.minimumCalls())
                .waitDurationInOpenState(b.openDuration())
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(t -> t instanceof TwinStoreException e
                        && (e.kind() == TwinStoreException.Kind.UNAVAILABLE || e.kind() == TwinStoreException.Kind.UNKNOWN_OUTCOME))
                .build());
    }

    @Bean
    DittoHttpTransport dittoHttpTransport(TwinProperties p, CircuitBreaker dittoCircuitBreaker) {
        return new DittoHttpTransport(p.ditto().apiBase(), p.ditto().connectTimeout(), p.ditto().requestTimeout(),
                dittoCircuitBreaker);
    }

    private static DittoIdentity identity(String role, TwinProperties.Credential c) {
        if (c == null || c.user() == null || c.password() == null) {
            throw new IllegalStateException("Ditto credential for role " + role + " is not configured");
        }
        return new DittoIdentity(role, c.user(), c.password().toCharArray());
    }

    private static String subject(TwinProperties p, TwinProperties.Credential c) {
        return p.ditto().subjectIssuer() + ":" + c.user();
    }

    @Bean
    TwinPolicyTemplate twinPolicyTemplate(TwinProperties p) {
        return new TwinPolicyTemplate(subject(p, p.ditto().operator()), subject(p, p.ditto().projector()),
                subject(p, p.ditto().reader()));
    }

    @Bean
    TwinThingStore twinThingStore(TwinProperties p, DittoHttpTransport http, ObjectMapper json) {
        return new DittoThingStore(http, identity("projector", p.ditto().projector()), json);
    }

    @Bean
    TwinPolicyStore twinPolicyStore(TwinProperties p, DittoHttpTransport http, ObjectMapper json) {
        return new DittoPolicyStore(http, identity("operator", p.ditto().operator()), json);
    }

    @Bean
    TwinReadModel twinReadModel(TwinProperties p, DittoHttpTransport http, ObjectMapper json) {
        Duration readTimeout = p.ditto().readTimeout() == null ? Duration.ofMillis(1500) : p.ditto().readTimeout();
        return new DittoReadModel(http, identity("reader", p.ditto().reader()), json, readTimeout);
    }

    // ------------------------------------------------------------------ JPA bookkeeping

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    @Bean
    JpaLeaseStore jpaLeaseStore(SpringDataLeaseRepository repo, TransactionTemplate tx, Clock clock) {
        return new JpaLeaseStore(repo, tx, clock);
    }

    @Bean
    JpaCheckpointStore jpaCheckpointStore(SpringDataCheckpointRepository repo, JpaLeaseStore leases,
                                          TransactionTemplate tx, Clock clock) {
        return new JpaCheckpointStore(repo, leases, tx, clock);
    }

    // ------------------------------------------------------------------ NARU (gRPC)

    @Bean(destroyMethod = "")
    ManagedChannel naruChannel(TwinProperties p) {
        NaruSourceTarget.Mode mode = NaruSourceTarget.mode(p.naru().mode());
        String target = NaruSourceTarget.resolve(mode, p.naru().target());
        log.info("NARU source mode={} target={}", mode.name().toLowerCase(), target);
        return NettyChannelBuilder.forTarget(target)
                .usePlaintext() // loopback/private network; per-peer bearer credential in metadata
                .keepAliveTime(p.naru().keepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(false)
                .maxInboundMessageSize(64 * 1024)
                .build();
    }

    @Bean
    LearningSource learningSource(TwinProperties p, ManagedChannel naruChannel) {
        if (p.naru().token() == null || p.naru().token().isBlank()) {
            throw new IllegalStateException("TWIN_TO_NARU_TOKEN must be configured");
        }
        return new GrpcLearningSource(naruChannel, new BearerCallCredentials(p.naru().token().toCharArray()),
                p.naru().snapshotTimeout(), p.naru().watchMaxDuration());
    }

    // ------------------------------------------------------------------ services

    @Bean
    ProjectionSettings projectionSettings(TwinProperties p) {
        TwinProperties.Projection x = p.projection();
        String writer = x.writerId();
        if (writer == null || writer.isBlank()) {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "local";
            }
            writer = "twin-" + host;
        }
        return new ProjectionSettings(writer, x.maxWriteAttempts(), x.queueCapacity(), x.backoffBase(), x.backoffCap(),
                x.actionableFailureDelay(), x.leaseTtl(), x.reconcileInterval(), x.drainTimeout());
    }

    @Bean
    ProvisioningService provisioningService(TwinPolicyStore policies, TwinPolicyTemplate template, LeaseStore leases,
                                            ProjectionDiagnostics diag, ProjectionSettings s) {
        return new ProvisioningService(policies, template, leases, diag, s.maxWriteAttempts());
    }

    @Bean
    ProjectionService projectionService(TwinThingStore things, CheckpointStore checkpoints, LeaseStore leases,
                                        ProvisioningService provisioning, ProjectionDiagnostics diag, ProjectionSettings s) {
        return new ProjectionService(things, checkpoints, leases, provisioning, diag, s.maxWriteAttempts());
    }

    @Bean
    DeletionLifecycle deletionLifecycle(TwinThingStore things, CheckpointStore checkpoints, LeaseStore leases,
                                        ProvisioningService provisioning, ProjectionService projection,
                                        ProjectionDiagnostics diag, ProjectionSettings s) {
        return new DeletionLifecycle(things, checkpoints, leases, provisioning, projection, diag, s.maxWriteAttempts());
    }

    @Bean
    ReconcileService reconcileService(LearningSource source, CheckpointStore checkpoints, ProjectionService projection,
                                      ProvisioningService provisioning, DeletionLifecycle deletion, ProjectionDiagnostics diag) {
        return new ReconcileService(source, checkpoints, projection, provisioning, deletion, diag);
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService projectionScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "twin-projection-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    ProfileProjector profileProjector(ProfileId profile, LearningSource source, LeaseStore leases, CheckpointStore checkpoints,
                                      ProjectionService projection, ReconcileService reconcile, DeletionLifecycle deletion,
                                      ProjectionDiagnostics diag, ProjectionSettings s, ScheduledExecutorService projectionScheduler) {
        return new ProfileProjector(profile, source, leases, checkpoints, projection, reconcile, deletion, diag, s,
                projectionScheduler);
    }

    @Bean
    ProjectionLifecycle projectionLifecycle(ProfileProjector projector, ManagedChannel naruChannel) {
        return new ProjectionLifecycle(projector, naruChannel);
    }

    @Bean
    SummaryReadService summaryReadService(ProfileId profile, CheckpointStore checkpoints, TwinReadModel reader,
                                          ProjectionDiagnostics diag) {
        return new SummaryReadService(profile, checkpoints, reader, diag);
    }

    @Bean
    LearningTwinModule learningTwinModule(ProfileId profile, SummaryReadService reads, ProfileProjector projector,
                                          CheckpointStore checkpoints, TwinReadModel reader, ProjectionDiagnostics diag,
                                          CircuitBreaker dittoCircuitBreaker) {
        return new LearningTwinModuleImpl(profile, reads, projector, checkpoints, reader, diag,
                () -> dittoCircuitBreaker.getState().name());
    }

    // ------------------------------------------------------------------ incoming adapter security

    @Bean
    FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilter(TwinProperties p) {
        String vertx = p.inbound().vertxToken();
        String maint = p.inbound().maintenanceToken();
        FilterRegistrationBean<ServiceTokenFilter> reg = new FilterRegistrationBean<>(new ServiceTokenFilter(
                vertx == null ? new char[0] : vertx.toCharArray(), maint == null ? null : maint.toCharArray()));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
