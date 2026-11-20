package org.naruprof.twin.dal.narugrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.naruprof.contract.learning.v1.ProjectionState;
import org.naruprof.contract.learning.v1.Summary;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.dal.api.SourceException.Kind;
import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;

/** Wire validation: profile fencing, oneof presence, uint64 carriage. */
final class ProjectionStateMapper {

    private ProjectionStateMapper() {
    }

    static SourceState toSource(ProjectionState m, ProfileId expected) {
        ProfileId p;
        try {
            p = ProfileId.parse(m.getProfileId());
        } catch (IllegalArgumentException e) {
            throw new SourceException(Kind.INCOMPATIBLE, "ProjectionState.profile_id is not a canonical UUID");
        }
        if (!p.equals(expected)) {
            throw new SourceException(Kind.INCOMPATIBLE, "ProjectionState for an unexpected profile");
        }
        U64 seq = U64.fromBits(m.getSequence());
        return switch (m.getStateCase()) {
            case SUMMARY -> {
                Summary s = m.getSummary();
                yield new SourceState.Live(p, seq, new LearningSummary(U64.fromBits(s.getTeachingSessions()),
                        U64.fromBits(s.getPracticeSessions()), U64.fromBits(s.getPropositions()),
                        U64.fromBits(s.getReasoningChains())));
            }
            case DELETED -> new SourceState.Tombstone(p, seq);
            case STATE_NOT_SET -> throw new SourceException(Kind.INCOMPATIBLE, "ProjectionState without state oneof");
        };
    }

    static SourceException classify(Throwable t) {
        if (t instanceof SourceException se) {
            return se;
        }
        Status s = Status.fromThrowable(t);
        Kind k = switch (s.getCode()) {
            case OUT_OF_RANGE -> Kind.OUT_OF_RANGE;
            case NOT_FOUND -> Kind.NOT_FOUND;
            case UNAUTHENTICATED, PERMISSION_DENIED -> Kind.AUTH;
            case UNAVAILABLE, DEADLINE_EXCEEDED, ABORTED, RESOURCE_EXHAUSTED, CANCELLED, INTERNAL, UNKNOWN -> Kind.TRANSIENT;
            case INVALID_ARGUMENT, UNIMPLEMENTED, FAILED_PRECONDITION, DATA_LOSS -> Kind.INCOMPATIBLE;
            default -> Kind.OTHER;
        };
        return new SourceException(k, "naru " + s.getCode() + (t instanceof StatusRuntimeException ? "" : " (" + t.getClass().getSimpleName() + ")"), t);
    }
}
