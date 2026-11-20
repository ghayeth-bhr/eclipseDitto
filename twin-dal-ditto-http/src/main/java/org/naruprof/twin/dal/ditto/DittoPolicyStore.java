package org.naruprof.twin.dal.ditto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.naruprof.twin.dal.api.TwinPolicyStore;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.naruprof.twin.dal.api.WriteOutcome;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Operator identity: dedicated per-profile Policy lifecycle. */
public class DittoPolicyStore implements TwinPolicyStore {

    private final DittoHttpTransport http;
    private final DittoIdentity operator;
    private final ObjectMapper json;

    public DittoPolicyStore(DittoHttpTransport http, DittoIdentity operator, ObjectMapper json) {
        this.http = http;
        this.operator = operator;
        this.json = json;
    }

    @Override
    public PolicyRead read(String policyId) {
        DittoHttpTransport.Response r = http.send(operator, "GET", DittoPaths.policy(policyId), null, null, null);
        return switch (r.status()) {
            case 200 -> new PolicyRead.Present(
                    r.header("ETag").orElseThrow(() -> new TwinStoreException(Kind.INCOMPATIBLE, "policy without ETag")),
                    parse(r.body()));
            case 404 -> new PolicyRead.Absent();
            case 403 -> throw new TwinStoreException(Kind.FORBIDDEN, "operator may not read policy");
            default -> throw new TwinStoreException(Kind.INCOMPATIBLE, "unexpected status reading policy: " + r.status());
        };
    }

    PolicyDocument parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            Map<String, Entry> entries = new LinkedHashMap<>();
            JsonNode es = root.path("entries");
            for (Iterator<String> it = es.fieldNames(); it.hasNext(); ) {
                String label = it.next();
                JsonNode e = es.get(label);
                Map<String, String> subjects = new LinkedHashMap<>();
                e.path("subjects").fields().forEachRemaining(s -> subjects.put(s.getKey(), s.getValue().path("type").asText("")));
                Map<String, Permissions> resources = new LinkedHashMap<>();
                e.path("resources").fields().forEachRemaining(res -> resources.put(res.getKey(),
                        new Permissions(strings(res.getValue().path("grant")), strings(res.getValue().path("revoke")))));
                entries.put(label, new Entry(subjects, resources));
            }
            JsonNode imports = root.path("imports");
            if (imports.isObject() && imports.size() > 0) {
                // imported entries change effective access: surface as a synthetic entry so the shape check fails
                entries.put("<imports>", new Entry(Map.of(), Map.of()));
            }
            return new PolicyDocument(root.path("policyId").asText(), entries);
        } catch (TwinStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new TwinStoreException(Kind.INCOMPATIBLE, "policy body not understood", null, e);
        }
    }

    private static Set<String> strings(JsonNode arr) {
        Set<String> s = new TreeSet<>();
        if (arr.isArray()) {
            arr.forEach(n -> s.add(n.asText()));
        }
        return s;
    }

    String render(PolicyDocument p) {
        ObjectNode root = json.createObjectNode();
        ObjectNode entries = root.putObject("entries");
        p.entries().forEach((label, e) -> {
            ObjectNode en = entries.putObject(label);
            ObjectNode subs = en.putObject("subjects");
            e.subjects().forEach((sid, type) -> subs.putObject(sid).put("type", type));
            ObjectNode res = en.putObject("resources");
            e.resources().forEach((path, perms) -> {
                ObjectNode pn = res.putObject(path);
                ArrayNode g = pn.putArray("grant");
                new TreeSet<>(perms.grant()).forEach(g::add);
                ArrayNode rv = pn.putArray("revoke");
                new TreeSet<>(perms.revoke()).forEach(rv::add);
            });
            en.put("importable", "never");
        });
        return root.toString();
    }

    @Override
    public WriteOutcome create(PolicyDocument policy) {
        return write("PUT", policy.policyId(), render(policy), "If-None-Match", "*");
    }

    @Override
    public WriteOutcome replace(PolicyDocument policy, String etag) {
        return write("PUT", policy.policyId(), render(policy), "If-Match", etag);
    }

    @Override
    public WriteOutcome delete(String policyId, String etag) {
        return write("DELETE", policyId, null, "If-Match", etag);
    }

    private WriteOutcome write(String method, String policyId, String body, String cond, String condValue) {
        DittoHttpTransport.Response r = http.send(operator, method, DittoPaths.policy(policyId), body, cond, condValue);
        return switch (r.status()) {
            case 201 -> WriteOutcome.CREATED;
            case 200, 204 -> WriteOutcome.APPLIED;
            case 412 -> WriteOutcome.PRECONDITION_FAILED;
            case 404 -> WriteOutcome.NOT_FOUND;
            case 403 -> throw new TwinStoreException(Kind.FORBIDDEN, "operator not allowed on policy");
            default -> throw new TwinStoreException(Kind.INCOMPATIBLE, "unexpected policy status " + r.status());
        };
    }
}
