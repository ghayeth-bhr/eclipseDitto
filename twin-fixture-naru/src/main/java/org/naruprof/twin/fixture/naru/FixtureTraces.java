package org.naruprof.twin.fixture.naru;

/**
 * The shared contract trace for one profile:
 * 0=(0,0,0,0) empty profile; 1=(1,0,0,0) teaching chat (kind absent reads as teach);
 * 2=(1,1,0,0) practice chat; 3=(1,1,2,1) two distinct propositions + one argument chain merged
 * in one commit (the canvas junction is derived geometry, not a stored chain).
 */
public final class FixtureTraces {

    public static final String CONTRACT_PROFILE = "f7933216-20db-49cb-90e0-3a9d5396966d";

    private FixtureTraces() {
    }

    public static void commonTrace(FakeLearningProjection naru, String profileId) {
        naru.createProfile(profileId);
        naru.commit(profileId, 1, 0, 0, 0);
        naru.commit(profileId, 1, 1, 0, 0);
        naru.commit(profileId, 1, 1, 2, 1);
    }
}
