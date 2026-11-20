package org.naruprof.twin.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.naruprof.twin.it.DittoItSupport.Resp;
import org.naruprof.twin.it.DittoItSupport.Who;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.naruprof.twin.it.DittoItSupport.ditto;
import static org.naruprof.twin.it.DittoItSupport.policyBody;
import static org.naruprof.twin.it.DittoItSupport.policyPath;
import static org.naruprof.twin.it.DittoItSupport.thingPath;

/**
 * Step 1 "prove access control before ingest": operator, projector, read-only, anonymous and a
 * second profile's subject against the real gateway, incl. existence hiding.
 */
class AccessControlIT {

    static final String A = DittoItSupport.newProfile();
    static final String B = DittoItSupport.newProfile();
    static final String PROPS = "{\"sequence\":\"0\",\"teachingSessions\":\"0\",\"practiceSessions\":\"0\",\"propositions\":\"0\",\"reasoningChains\":\"0\"}";

    @BeforeAll
    static void provision() {
        assumeTrue(DittoItSupport.dittoReachable(), "pinned Ditto stack not reachable at " + DittoItSupport.API);
        for (String[] pr : new String[][]{{A, "READER"}, {B, "READER_B"}}) {
            Resp pol = ditto(Who.OPERATOR, "PUT", policyPath(pr[0]), policyBody(Who.valueOf(pr[1])), Map.of("If-None-Match", "*"));
            assertThat(pol.status()).as("operator creates policy").isEqualTo(201);
            Resp th = ditto(Who.PROJECTOR, "PUT", thingPath(pr[0]),
                    "{\"policyId\":\"naruprof.learning:policy-" + pr[0] + "\",\"features\":{\"learning\":{\"properties\":" + PROPS + "}}}",
                    Map.of("If-None-Match", "*"));
            assertThat(th.status()).as("projector creates thing bound to the policy").isEqualTo(201);
        }
    }

    @AfterAll
    static void cleanup() {
        if (DittoItSupport.dittoReachable()) {
            DittoItSupport.purge(A);
            DittoItSupport.purge(B);
        }
    }

    @Test
    void anonymousIsRejectedAndCannotSpoofASubject() {
        assertThat(ditto(Who.ANONYMOUS, "GET", thingPath(A)).status()).isEqualTo(401);
        assertThat(ditto(Who.ANONYMOUS, "GET", thingPath(A), null,
                Map.of("x-ditto-pre-authenticated", "nginx:naruprof-twin-operator")).status()).isEqualTo(401);
        // an authenticated reader claiming to be the operator stays the reader
        assertThat(ditto(Who.READER, "GET", policyPath(A), null,
                Map.of("x-ditto-pre-authenticated", "nginx:naruprof-twin-operator")).status()).isEqualTo(404);
    }

    @Test
    void creationIsScopedToNamespaceAndRole() {
        String other = DittoItSupport.newProfile();
        assertThat(ditto(Who.PROJECTOR, "PUT", policyPath(other), policyBody(Who.READER), Map.of("If-None-Match", "*")).json()
                .path("error").asText()).isEqualTo("entity.notcreatable");
        assertThat(ditto(Who.READER, "PUT", policyPath(other), policyBody(Who.READER), Map.of("If-None-Match", "*")).status()).isEqualTo(403);
        assertThat(ditto(Who.OPERATOR, "PUT", "/things/naruprof.learning:" + other, "{\"policyId\":\"naruprof.learning:policy-" + A + "\"}",
                Map.of("If-None-Match", "*")).status()).isEqualTo(403);
        assertThat(ditto(Who.OPERATOR, "PUT", "/policies/other.namespace:policy-" + other, policyBody(Who.READER), null).status()).isEqualTo(403);
        assertThat(ditto(Who.PROJECTOR, "PUT", "/things/other.namespace:" + other, "{}", null).status()).isEqualTo(403);
    }

    @Test
    void operatorAdministersPolicyButSeesNoThingData() {
        Resp p = ditto(Who.OPERATOR, "GET", policyPath(A));
        assertThat(p.status()).isEqualTo(200);
        assertThat(p.etag()).startsWith("\"rev:");
        assertThat(ditto(Who.OPERATOR, "GET", thingPath(A)).status()).isEqualTo(404); // existence hiding
        Resp again = ditto(Who.OPERATOR, "PUT", policyPath(A), policyBody(Who.READER), Map.of("If-None-Match", "*"));
        assertThat(again.status()).as("conditional creation of an existing policy").isEqualTo(412);
    }

    @Test
    void projectorWritesItsThingButCannotMutatePolicy() {
        Resp t = ditto(Who.PROJECTOR, "GET", thingPath(A));
        assertThat(t.status()).isEqualTo(200);
        assertThat(ditto(Who.PROJECTOR, "GET", policyPath(A)).status()).isEqualTo(200); // READ to reference it
        assertThat(ditto(Who.PROJECTOR, "PUT", policyPath(A), policyBody(Who.READER), null).status()).isEqualTo(403);
        assertThat(ditto(Who.PROJECTOR, "DELETE", policyPath(A)).status()).isEqualTo(403);
        // stale ETag is refused, exact ETag applies
        String body = "{\"policyId\":\"naruprof.learning:policy-" + A + "\",\"features\":{\"learning\":{\"properties\":" + PROPS + "}}}";
        assertThat(ditto(Who.PROJECTOR, "PUT", thingPath(A), body, Map.of("If-Match", "\"rev:999999\"")).status()).isEqualTo(412);
        assertThat(ditto(Who.PROJECTOR, "PUT", thingPath(A), body, Map.of("If-Match", t.etag())).status()).isEqualTo(204);
        // one installation projector subject is granted on every profile policy it owns, by design
        assertThat(ditto(Who.PROJECTOR, "GET", thingPath(B)).status()).isEqualTo(200);
    }

    @Test
    void readerOnlyReadsTheLearningFeature() {
        Resp r = ditto(Who.READER, "GET", thingPath(A) + "/features/learning/properties");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("sequence").asText()).isEqualTo("0");
        Resp whole = ditto(Who.READER, "GET", thingPath(A));
        assertThat(whole.json().has("policyId")).as("partial view hides the policy binding").isFalse();
        assertThat(ditto(Who.READER, "PUT", thingPath(A) + "/features/learning/properties/sequence", "\"9\"", null).status()).isEqualTo(403);
        assertThat(ditto(Who.READER, "DELETE", thingPath(A)).status()).isEqualTo(403);
        assertThat(ditto(Who.READER, "PUT", thingPath(A) + "/policyId", "\"naruprof.learning:policy-" + B + "\"", null).status()).isEqualTo(403);
        assertThat(ditto(Who.READER, "GET", policyPath(A)).status()).isEqualTo(404);
        assertThat(ditto(Who.READER, "PUT", policyPath(A), policyBody(Who.READER), null).status()).isEqualTo(403);
        assertThat(ditto(Who.READER, "DELETE", policyPath(A)).status()).isIn(403, 404);
    }

    @Test
    void secondProfilesSubjectGetsExistenceHiding() {
        Resp other = ditto(Who.READER_B, "GET", thingPath(A) + "/features/learning/properties");
        Resp missing = ditto(Who.READER_B, "GET", thingPath(DittoItSupport.newProfile()) + "/features/learning/properties");
        assertThat(other.status()).isEqualTo(404);
        assertThat(missing.status()).isEqualTo(404); // indistinguishable from a non-existent twin
        assertThat(ditto(Who.READER_B, "GET", thingPath(A)).status()).isEqualTo(404);
        assertThat(ditto(Who.READER, "GET", thingPath(B) + "/features/learning/properties").status()).isEqualTo(404);
        assertThat(ditto(Who.READER_B, "GET", thingPath(B) + "/features/learning/properties").status()).isEqualTo(200);
    }

    @Test
    void liveMessagesSearchAndManagementAreNotExposed() {
        assertThat(ditto(Who.PROJECTOR, "POST", thingPath(A) + "/inbox/messages/x", "{}", null).status()).isEqualTo(404);
        assertThat(ditto(Who.READER, "GET", "/search/things").status()).isEqualTo(404);
        assertThat(ditto(Who.OPERATOR, "GET", "/connections").status()).isEqualTo(404);
    }
}
