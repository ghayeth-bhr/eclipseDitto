package org.naruprof.twin.ws.rest;

import org.naruprof.twin.service.api.LearningTwinModule;
import org.naruprof.twin.service.api.SummaryResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Private summary GET consumed by Vert.x ({@code GET /api/naruprof/twin/summary}). Authenticated
 * by {@link ServiceTokenFilter}. Read-only: no native Thing/Policy mutation is exposed.
 */
@RestController
public class TwinSummaryWS {

    private final LearningTwinModule twin;

    public TwinSummaryWS(LearningTwinModule twin) {
        this.twin = twin;
    }

    @GetMapping(path = "/internal/v1/profiles/{profileId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> summary(@PathVariable String profileId) {
        SummaryResult r = twin.readSummary(profileId);
        return switch (r) {
            case SummaryResult.Found f -> json(HttpStatus.OK, f.view());
            case SummaryResult.Missing m -> json(HttpStatus.NOT_FOUND, Map.of("code", "twin_missing"));
            case SummaryResult.Unavailable u -> json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("code", "twin_unavailable"));
            case SummaryResult.Forbidden x -> json(HttpStatus.FORBIDDEN, Map.of("code", "forbidden"));
            case SummaryResult.Invalid x -> json(HttpStatus.BAD_REQUEST, Map.of("code", "invalid_profile_id"));
        };
    }

    /** Read-only facade: every mutating method on the summary resource is rejected. */
    @RequestMapping(path = "/internal/v1/profiles/{profileId}/summary",
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Object> unsupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.GET, HttpMethod.HEAD)
                .cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "method_not_allowed"));
    }

    static ResponseEntity<Object> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
