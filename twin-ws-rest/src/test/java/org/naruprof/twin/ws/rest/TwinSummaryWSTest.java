package org.naruprof.twin.ws.rest;

import org.junit.jupiter.api.Test;
import org.naruprof.twin.infra.TwinSummaryView;
import org.naruprof.twin.service.api.LearningTwinModule;
import org.naruprof.twin.service.api.ProjectionStatusView;
import org.naruprof.twin.service.api.Readiness;
import org.naruprof.twin.service.api.ReconcileOutcome;
import org.naruprof.twin.service.api.SummaryResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TwinSummaryWSTest {

    static final String P = "f7933216-20db-49cb-90e0-3a9d5396966d";
    static final String VERTX = "test-only-vertx-to-twin-token";
    static final String MAINT = "test-only-maintenance-token";

    final AtomicReference<SummaryResult> next = new AtomicReference<>();
    final LearningTwinModule module = new LearningTwinModule() {
        public SummaryResult readSummary(String id) { return next.get(); }
        public boolean isLive() { return true; }
        public Readiness readiness() { return new Readiness(false, true, false, "projection not initialized"); }
        public ProjectionStatusView status() {
            return new ProjectionStatusView(P, "LIVE", "3", "3", "0", "STREAMING", null, null, "CLOSED", null, Map.of());
        }
        public ReconcileOutcome reconcile(String id, boolean accept) { return new ReconcileOutcome("LIVE", "3", "ok"); }
    };

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TwinSummaryWS(module), new TwinOperationsWS(module))
            .addFilters(new ServiceTokenFilter(VERTX.toCharArray(), MAINT.toCharArray())).build();

    String url() {
        return "/internal/v1/profiles/" + P + "/summary";
    }

    @Test
    void foundReturnsExactSixFieldContractBody() throws Exception {
        next.set(new SummaryResult.Found(new TwinSummaryView(P, "3", "1", "1", "2", "1")));
        mvc.perform(get(url()).header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"profileId\":\"" + P + "\",\"sequence\":\"3\",\"teachingSessions\":\"1\","
                        + "\"practiceSessions\":\"1\",\"propositions\":\"2\",\"reasoningChains\":\"1\"}", true));
    }

    @Test
    void missingUnavailableForbiddenInvalid() throws Exception {
        next.set(new SummaryResult.Missing());
        mvc.perform(get(url()).header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"twin_missing\"}", true));
        next.set(new SummaryResult.Unavailable("bootstrapping"));
        mvc.perform(get(url()).header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"code\":\"twin_unavailable\"}", true));
        next.set(new SummaryResult.Forbidden());
        mvc.perform(get(url()).header("Authorization", "Bearer " + VERTX)).andExpect(status().isForbidden());
        next.set(new SummaryResult.Invalid());
        mvc.perform(get(url()).header("Authorization", "Bearer " + VERTX)).andExpect(status().isBadRequest());
    }

    @Test
    void callersAreAuthenticatedPerRole() throws Exception {
        next.set(new SummaryResult.Missing());
        mvc.perform(get(url())).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get(url()).header("Authorization", "Bearer wrong-token-value")).andExpect(status().isUnauthorized());
        mvc.perform(get(url()).header("Authorization", "Basic " + VERTX)).andExpect(status().isUnauthorized());
        // the maintenance credential is valid but not for the Vert.x surface, and vice versa
        mvc.perform(get(url()).header("Authorization", "Bearer " + MAINT)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/projection/status").header("Authorization", "Bearer " + VERTX)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/projection/status").header("Authorization", "Bearer " + MAINT)).andExpect(status().isOk());
        mvc.perform(post("/internal/v1/maintenance/profiles/" + P + "/reconcile").header("Authorization", "Bearer " + MAINT))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsOpenAndNoNativeProxyExists() throws Exception {
        mvc.perform(get("/internal/health/live")).andExpect(status().isOk());
        mvc.perform(get("/internal/health/ready")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/2/things/x").header("Authorization", "Bearer " + VERTX)).andExpect(status().isNotFound());
        mvc.perform(post("/internal/v1/profiles/" + P + "/summary").header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().json("{\"code\":\"method_not_allowed\"}", true));
        mvc.perform(delete("/internal/v1/profiles/" + P + "/summary").header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isMethodNotAllowed());
        // mutation attempts without a credential are still rejected by authentication first
        mvc.perform(put("/internal/v1/profiles/" + P + "/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyExactHealthGetsAreExempt() throws Exception {
        mvc.perform(get("/internal/health/live/")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/health/other")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/health/live")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/internal/v1/projection/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/anything")).andExpect(status().isNotFound());
        // health never authorizes a protected path reached through it
        mvc.perform(get(url()).header("Authorization", "Bearer " + MAINT)).andExpect(status().isForbidden());
    }

    @Test
    void wrongProfileIsForbiddenAndInvalidIdIsBadRequestAfterAuthentication() throws Exception {
        next.set(new SummaryResult.Invalid());
        mvc.perform(get("/internal/v1/profiles/not-a-uuid/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/profiles/not-a-uuid/summary").header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"invalid_profile_id\"}", true));
        next.set(new SummaryResult.Forbidden());
        mvc.perform(get("/internal/v1/profiles/00000000-0000-4000-8000-000000000000/summary")
                        .header("Authorization", "Bearer " + VERTX))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"code\":\"forbidden\"}", true));
    }
}
