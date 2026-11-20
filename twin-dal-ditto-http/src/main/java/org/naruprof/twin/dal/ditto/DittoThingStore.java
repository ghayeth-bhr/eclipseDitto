package org.naruprof.twin.dal.ditto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.naruprof.twin.dal.api.TwinThingStore;
import org.naruprof.twin.dal.api.WriteOutcome;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinIds;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Projector identity: persisted Thing CRUD with native revision preconditions. */
public class DittoThingStore implements TwinThingStore {

    /** Top-level Thing fields the module carries over untouched on replace. */
    static final Set<String> PRESERVED_FIELDS = Set.of("attributes", "definition");

    private final DittoHttpTransport http;
    private final DittoIdentity projector;
    private final ObjectMapper json;

    public DittoThingStore(DittoHttpTransport http, DittoIdentity projector, ObjectMapper json) {
        this.http = http;
        this.projector = projector;
        this.json = json;
    }

    @Override
    public ThingRead read(ProfileId profile) {
        String thingId = TwinIds.thingId(profile);
        DittoHttpTransport.Response r = http.send(projector, "GET", DittoPaths.thing(thingId), null, null, null);
        return switch (r.status()) {
            case 200 -> parse(r);
            case 404 -> new ThingRead.Absent();
            case 403 -> throw new TwinStoreException(Kind.FORBIDDEN, "projector may not read the thing");
            default -> throw new TwinStoreException(Kind.INCOMPATIBLE, "unexpected status reading thing: " + r.status());
        };
    }

    private ThingRead.Present parse(DittoHttpTransport.Response r) {
        String etag = r.header("ETag").orElseThrow(() ->
                new TwinStoreException(Kind.INCOMPATIBLE, "thing response without ETag"));
        JsonNode root = tree(r.body());
        String thingId = text(root, "thingId");
        String policyId = text(root, "policyId");
        Map<String, Object> preserved = new LinkedHashMap<>();
        for (String f : PRESERVED_FIELDS) {
            if (root.has(f)) {
                preserved.put(f, json.convertValue(root.get(f), Object.class));
            }
        }
        Set<String> otherFeatures = new LinkedHashSet<>();
        Optional<Map<String, Object>> learning = Optional.empty();
        JsonNode features = root.path("features");
        for (Iterator<String> it = features.fieldNames(); it.hasNext(); ) {
            String fid = it.next();
            JsonNode feature = features.get(fid);
            if (!TwinIds.FEATURE.equals(fid)) {
                otherFeatures.add(fid);
                continue;
            }
            for (Iterator<String> parts = feature.fieldNames(); parts.hasNext(); ) {
                String part = parts.next();
                if (!"properties".equals(part)) {
                    otherFeatures.add(fid + "/" + part); // e.g. desiredProperties, definition: drift
                }
            }
            JsonNode props = feature.path("properties");
            Map<String, Object> m = new LinkedHashMap<>();
            if (props.isObject()) {
                props.fields().forEachRemaining(e -> m.put(e.getKey(), json.convertValue(e.getValue(), Object.class)));
            }
            learning = Optional.of(m);
        }
        // fields that are neither preserved nor owned also count as drift on our own thing
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!f.equals("thingId") && !f.equals("policyId") && !f.equals("features") && !PRESERVED_FIELDS.contains(f)) {
                otherFeatures.add("/" + f);
            }
        }
        return new ThingRead.Present(etag, thingId, policyId, learning, Set.copyOf(otherFeatures), Map.copyOf(preserved));
    }

    @Override
    public WriteOutcome create(ProfileId profile, String policyId, Map<String, String> learningProperties) {
        ObjectNode body = json.createObjectNode();
        body.put("policyId", policyId);
        body.putObject("features").putObject(TwinIds.FEATURE).set("properties", json.valueToTree(learningProperties));
        return write("PUT", TwinIds.thingId(profile), body, "If-None-Match", "*");
    }

    @Override
    public WriteOutcome replace(ProfileId profile, ThingRead.Present base, Map<String, String> learningProperties) {
        ObjectNode body = json.createObjectNode();
        body.put("policyId", base.policyId()); // never changes the binding
        base.preserved().forEach((k, v) -> body.set(k, json.valueToTree(v)));
        body.putObject("features").putObject(TwinIds.FEATURE).set("properties", json.valueToTree(learningProperties));
        return write("PUT", TwinIds.thingId(profile), body, "If-Match", base.etag());
    }

    @Override
    public WriteOutcome delete(ProfileId profile, String etag) {
        return write("DELETE", TwinIds.thingId(profile), null, "If-Match", etag);
    }

    private WriteOutcome write(String method, String thingId, ObjectNode body, String cond, String condValue) {
        String payload = body == null ? null : body.toString();
        DittoHttpTransport.Response r = http.send(projector, method, DittoPaths.thing(thingId), payload, cond, condValue);
        return switch (r.status()) {
            case 201 -> WriteOutcome.CREATED;
            case 200, 204 -> WriteOutcome.APPLIED;
            case 412 -> WriteOutcome.PRECONDITION_FAILED;
            case 404 -> {
                // 404 on create means the referenced policy is not visible to the projector
                if (r.body() != null && r.body().contains("policy.notfound")) {
                    throw new TwinStoreException(Kind.CONFLICT, "referenced policy not visible to projector");
                }
                yield WriteOutcome.NOT_FOUND;
            }
            case 403 -> throw new TwinStoreException(Kind.FORBIDDEN, "projector not allowed: " + errorCode(r));
            case 400 -> throw new TwinStoreException(Kind.INCOMPATIBLE, "ditto rejected thing: " + errorCode(r));
            default -> throw new TwinStoreException(Kind.INCOMPATIBLE, "unexpected status " + r.status() + " " + errorCode(r));
        };
    }

    private String errorCode(DittoHttpTransport.Response r) {
        try {
            return json.readTree(r.body()).path("error").asText("?");
        } catch (Exception e) {
            return "?";
        }
    }

    private JsonNode tree(String body) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new TwinStoreException(Kind.INCOMPATIBLE, "thing body is not JSON", null, e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            throw new TwinStoreException(Kind.INCOMPATIBLE, "thing without textual " + field);
        }
        return v.asText();
    }
}
