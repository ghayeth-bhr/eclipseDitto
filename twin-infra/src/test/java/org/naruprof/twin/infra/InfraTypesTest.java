package org.naruprof.twin.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfraTypesTest {

    static final String P = "f7933216-20db-49cb-90e0-3a9d5396966d";

    @Test
    void u64ComparesUnsignedAndRoundTripsMax() {
        U64 max = U64.parse("18446744073709551615");
        assertThat(max.toString()).isEqualTo("18446744073709551615");
        assertThat(max.isGreaterThan(U64.of(Long.MAX_VALUE))).isTrue();
        assertThat(U64.parse("3").compareTo(U64.parse("2"))).isPositive();
        assertThatThrownBy(max::next).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void u64RejectsNonCanonicalText() {
        for (String bad : new String[]{"", "-1", "+1", "01", "1.0", " 1", "18446744073709551616", "1e3", "abc"}) {
            assertThat(U64.isCanonical(bad)).as(bad).isFalse();
        }
        assertThat(U64.isCanonical("0")).isTrue();
    }

    @Test
    void profileIdIsCanonicalLowercaseUuid() {
        assertThat(ProfileId.parse(P.toUpperCase()).value()).isEqualTo(P);
        assertThat(ProfileId.parse(P)).isEqualTo(ProfileId.parse(P.toUpperCase()));
        assertThatThrownBy(() -> ProfileId.parse("1-2-3-4-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileId.parse("../" + P)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityIdsFollowContract() {
        ProfileId p = ProfileId.parse(P);
        assertThat(TwinIds.thingId(p)).isEqualTo("naruprof.learning:" + P);
        assertThat(TwinIds.policyId(p)).isEqualTo("naruprof.learning:policy-" + P);
    }

    @Test
    void summaryViewRendersSixDecimalFields() {
        ProfileId p = ProfileId.parse(P);
        TwinSummaryView v = TwinSummaryView.of(p, new TwinState(U64.of(3), LearningSummary.of(1, 1, 2, 1)));
        assertThat(v).isEqualTo(new TwinSummaryView(P, "3", "1", "1", "2", "1"));
    }
}
