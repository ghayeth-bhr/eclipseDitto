package org.naruprof.twin.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.naruprof.twin.fixture.naru.FakeLearningProjection;
import org.naruprof.twin.fixture.naru.FixtureTraces;
import org.naruprof.twin.it.DittoItSupport.App;
import org.naruprof.twin.it.DittoItSupport.Naru;
import org.naruprof.twin.it.DittoItSupport.Resp;
import org.naruprof.twin.it.DittoItSupport.Who;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.naruprof.twin.it.DittoItSupport.await;
import static org.naruprof.twin.it.DittoItSupport.ditto;
import static org.naruprof.twin.it.DittoItSupport.http;

/**
 * First vertical slice with the contract profile: real Policy + Thing, one fixture revision
 * stream over real gRPC, read through the read-only subject and the private REST facade.
 */
class VerticalSliceIT {

    static final String P = FixtureTraces.CONTRACT_PROFILE;
    static final String CONTRACT_JSON = "{\"profileId\":\"f7933216-20db-49cb-90e0-3a9d5396966d\",\"sequence\":\"3\","
            + "\"teachingSessions\":\"1\",\"practiceSessions\":\"1\",\"propositions\":\"2\",\"reasoningChains\":\"1\"}";

    static Naru naru;
    static App app;

    @BeforeAll
    static void up() throws Exception {
        assumeTrue(DittoItSupport.dittoReachable(), "pinned Ditto stack not reachable at " + DittoItSupport.API);
        DittoItSupport.purge(P); // disposable fixture: start from an absent twin
        naru = new Naru();
        naru.fake.createProfile(P); // revision 0, four zero counts
        app = new App(P, DittoItSupport.tempDir(), naru);
    }

    @AfterAll
    static void down() throws Exception {
        if (app != null) {
            app.close();
        }
        if (naru != null) {
            naru.close();
        }
    }

    @Test
    void emptyProfileThenCommonTraceMatchesSharedJson() throws Exception {
        app.start();
        // readiness flips once the empty twin is confirmed
        await("ready", Duration.ofSeconds(30), () -> http("GET", app.base() + "/internal/health/ready", null).status() == 200);
        Resp empty = app.summary();
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.json()).isEqualTo(DittoItSupport.JSON.readTree("{\"profileId\":\"" + P
                + "\",\"sequence\":\"0\",\"teachingSessions\":\"0\",\"practiceSessions\":\"0\",\"propositions\":\"0\",\"reasoningChains\":\"0\"}"));

        naru.fake.commit(P, 1, 0, 0, 0); // teaching chat (missing kind counts as teaching)
        naru.fake.commit(P, 1, 1, 0, 0); // practice chat
        naru.fake.commit(P, 1, 1, 2, 1); // two propositions + one argument chain in one commit
        await("revision 3", Duration.ofSeconds(20), () -> "3".equals(app.summarySequence()));

        Resp s = app.summary();
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.body()).isEqualTo(CONTRACT_JSON); // byte-exact incl. field order

        // the native Thing, read by the projector, is exactly the module's example body
        Resp thing = ditto(Who.PROJECTOR, "GET", DittoItSupport.thingPath(P));
        JsonNode t = thing.json();
        assertThat(t.path("thingId").asText()).isEqualTo("naruprof.learning:" + P);
        ((com.fasterxml.jackson.databind.node.ObjectNode) t).remove("thingId");
        assertThat(t).isEqualTo(DittoItSupport.JSON.readTree("""
                {"policyId":"naruprof.learning:policy-f7933216-20db-49cb-90e0-3a9d5396966d",
                 "features":{"learning":{"properties":{"sequence":"3","teachingSessions":"1","practiceSessions":"1",
                 "propositions":"2","reasoningChains":"1"}}}}"""));

        // the read-only subject reads it too, and cannot write
        assertThat(ditto(Who.READER, "GET", DittoItSupport.thingPath(P) + "/features/learning/properties").json()
                .path("sequence").asText()).isEqualTo("3");
        assertThat(ditto(Who.READER, "PUT", DittoItSupport.thingPath(P) + "/features/learning/properties/sequence", "\"4\"", null)
                .status()).isEqualTo(403);

        // duplicate 3 and late 2: no count change, no sequence rollback, no native write
        String etag = thing.etag();
        naru.fake.injectToWatchers(FakeLearningProjection.state(P, 3, 1, 1, 2, 1));
        naru.fake.injectToWatchers(FakeLearningProjection.state(P, 2, 1, 1, 0, 0));
        Thread.sleep(1500);
        assertThat(app.summary().body()).isEqualTo(CONTRACT_JSON);
        assertThat(ditto(Who.PROJECTOR, "GET", DittoItSupport.thingPath(P)).etag()).isEqualTo(etag);

        // private facade authentication
        String url = app.base() + "/internal/v1/profiles/" + P + "/summary";
        assertThat(http("GET", url, null).status()).isEqualTo(401);
        assertThat(http("GET", url, "not-the-token").status()).isEqualTo(401);
        assertThat(http("GET", url, DittoItSupport.MAINT_TOKEN).status()).isEqualTo(403);
        assertThat(http("GET", app.base() + "/internal/v1/profiles/" + DittoItSupport.newProfile() + "/summary",
                DittoItSupport.VERTX_TOKEN).status()).isEqualTo(403);
        assertThat(http("GET", app.base() + "/internal/v1/profiles/" + P.toUpperCase() + "/summary",
                DittoItSupport.VERTX_TOKEN).body()).isEqualTo(CONTRACT_JSON);

        JsonNode status = app.status().json();
        assertThat(status.path("appliedSequence").asText()).isEqualTo("3");
        assertThat(status.path("consumerState").asText()).isEqualTo("STREAMING");
        assertThat(status.path("replayLag").asText()).isEqualTo("0");
    }
}
