package org.naruprof.twin.ws.rest;

import org.naruprof.twin.service.api.LearningTwinModule;
import org.naruprof.twin.service.api.Readiness;
import org.naruprof.twin.service.api.ReconcileOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Private management surface: health, projection status/metrics, explicit reconciliation. */
@RestController
public class TwinOperationsWS {

    private final LearningTwinModule twin;

    public TwinOperationsWS(LearningTwinModule twin) {
        this.twin = twin;
    }

    @GetMapping(path = "/internal/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> live() {
        return twin.isLive()
                ? TwinSummaryWS.json(HttpStatus.OK, Map.of("status", "live"))
                : TwinSummaryWS.json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("status", "down"));
    }

    @GetMapping(path = "/internal/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> ready() {
        Readiness r = twin.readiness();
        Map<String, Object> body = Map.of("status", r.ready() ? "ready" : "not_ready",
                "nativeReadAuthenticated", r.nativeReadAuthenticated(),
                "projectionInitialized", r.projectionInitialized(), "detail", r.detail());
        return TwinSummaryWS.json(r.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    @GetMapping(path = "/internal/v1/projection/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> status() {
        return TwinSummaryWS.json(HttpStatus.OK, twin.status());
    }

    @PostMapping(path = "/internal/v1/maintenance/profiles/{profileId}/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> reconcile(@PathVariable String profileId,
                                            @RequestParam(defaultValue = "false") boolean acceptSourceRestore) {
        ReconcileOutcome o = twin.reconcile(profileId, acceptSourceRestore);
        HttpStatus s = switch (o.result()) {
            case "INVALID" -> HttpStatus.BAD_REQUEST;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "RESTORE_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return TwinSummaryWS.json(s, o);
    }
}
