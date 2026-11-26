package org.naruprof.twin.app;

import org.junit.jupiter.api.Test;
import org.naruprof.twin.app.NaruSourceTarget.Mode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaruSourceTargetTest {

    @Test
    void integratedIsTheDefaultAndRequiresAnExplicitRealTarget() {
        assertThat(NaruSourceTarget.mode(null)).isEqualTo(Mode.INTEGRATED);
        assertThat(NaruSourceTarget.mode(" ")).isEqualTo(Mode.INTEGRATED);
        assertThatThrownBy(() -> NaruSourceTarget.resolve(Mode.INTEGRATED, null))
                .hasMessageContaining("NARU_GRPC_TARGET must be set");
        assertThatThrownBy(() -> NaruSourceTarget.resolve(Mode.INTEGRATED, ""))
                .hasMessageContaining("NARU_GRPC_TARGET must be set");
        assertThat(NaruSourceTarget.resolve(Mode.INTEGRATED, " 100.64.0.7:9092 ")).isEqualTo("100.64.0.7:9092");
        assertThat(NaruSourceTarget.resolve(Mode.INTEGRATED, "naru-box.tailnet.ts.net:9092"))
                .isEqualTo("naru-box.tailnet.ts.net:9092");
    }

    @Test
    void integratedRefusesTheBundledFakeAndLoopback() {
        for (String t : new String[]{"fake-naru:9092", "127.0.0.1:9092", "localhost:9092", "[::1]:9092", "0.0.0.0:9092"}) {
            assertThatThrownBy(() -> NaruSourceTarget.resolve(Mode.INTEGRATED, t)).hasMessageContaining("integrated mode");
        }
    }

    @Test
    void fakeModeMustBeExplicitAndStillNeedsATarget() {
        assertThat(NaruSourceTarget.mode("fake")).isEqualTo(Mode.FAKE);
        assertThat(NaruSourceTarget.resolve(Mode.FAKE, "fake-naru:9092")).isEqualTo("fake-naru:9092");
        assertThatThrownBy(() -> NaruSourceTarget.resolve(Mode.FAKE, "")).hasMessageContaining("fake mode");
        assertThatThrownBy(() -> NaruSourceTarget.mode("real")).hasMessageContaining("TWIN_SOURCE_MODE");
    }

    @Test
    void malformedTargetsAreRejected() {
        for (String t : new String[]{"100.64.0.7", "100.64.0.7:", ":9092", "100.64.0.7:http", "100.64.0.7:70000"}) {
            assertThatThrownBy(() -> NaruSourceTarget.resolve(Mode.INTEGRATED, t)).isInstanceOf(IllegalStateException.class);
        }
    }
}
