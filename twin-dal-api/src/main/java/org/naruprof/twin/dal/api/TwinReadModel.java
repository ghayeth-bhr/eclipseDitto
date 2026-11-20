package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.ProfileId;

import java.util.Map;

/** Read-only identity port: the only native access used by the summary read path. */
public interface TwinReadModel {

    LearningRead readLearning(ProfileId profile);

    /** Authenticated no-op read proving the read credential works (GET /api/2/whoami). */
    void verifyAuthenticated();

    sealed interface LearningRead {
        record Found(Map<String, Object> properties) implements LearningRead {
        }

        /** 404, including Ditto's existence hiding for things the reader may not see. */
        record NotVisible() implements LearningRead {
        }
    }
}
