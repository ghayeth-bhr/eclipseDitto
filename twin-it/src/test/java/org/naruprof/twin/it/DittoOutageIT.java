package org.naruprof.twin.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.naruprof.twin.fixture.naru.FixtureTraces;
import org.naruprof.twin.it.DittoItSupport.App;
import org.naruprof.twin.it.DittoItSupport.Naru;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.naruprof.twin.it.DittoItSupport.await;

/**
 * Stops the real Ditto gateway while NARU keeps accepting lessons. Learning (the source) stays
 * available; the summary reports 503 during the outage, then the applied revision catches up.
 * Disable with -Dditto.it.allowStop=false on shared stacks.
 */
class DittoOutageIT {

    static String p;
    static Naru naru;
    static App app;

    @BeforeAll
    static void up() throws Exception {
        assumeTrue(DittoItSupport.dittoReachable(), "pinned Ditto stack not reachable");
        assumeTrue(!"false".equals(System.getProperty("ditto.it.allowStop")), "stopping Ditto disabled");
        p = DittoItSupport.newProfile();
        naru = new Naru();
        app = new App(p, DittoItSupport.tempDir(), naru);
    }

    @AfterAll
    static void down() throws Exception {
        try {
            DittoItSupport.compose("start", "gateway");
            await("gateway back", Duration.ofSeconds(180), DittoItSupport::dittoReachable);
        } finally {
            if (app != null) {
                app.close();
            }
            if (naru != null) {
                naru.close();
            }
            DittoItSupport.purge(p);
        }
    }

    @Test
    void stopDittoWhileNaruAcceptsALesson() throws Exception {
        FixtureTraces.commonTrace(naru.fake, p);
        app.start();
        await("revision 3", Duration.ofSeconds(30), () -> "3".equals(app.summarySequence()));

        long t0 = System.nanoTime();
        DittoItSupport.compose("stop", "gateway");
        long seqAccepted = naru.fake.commit(p, 2, 1, 2, 1); // NARU accepts a new lesson during the outage
        assertThat(seqAccepted).isEqualTo(4);
        await("503 during outage", Duration.ofSeconds(30), () -> app.summary().status() == 503);
        assertThat(app.summary().body()).isEqualTo("{\"code\":\"twin_unavailable\"}");
        assertThat(app.status().json().path("lastSourceSequence").asText()).isEqualTo("4");

        DittoItSupport.compose("start", "gateway");
        await("revision 4 after outage", Duration.ofSeconds(240), () -> "4".equals(app.summarySequence()));
        System.out.printf("ditto outage drill: catch-up %d s after stop%n", (System.nanoTime() - t0) / 1_000_000_000L);
        await("streaming again", Duration.ofSeconds(30),
                () -> "STREAMING".equals(app.status().json().path("consumerState").asText()));
    }
}
