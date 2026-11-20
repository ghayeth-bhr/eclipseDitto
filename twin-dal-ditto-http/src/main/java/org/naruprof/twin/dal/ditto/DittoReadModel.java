package org.naruprof.twin.dal.ditto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.naruprof.twin.dal.api.TwinReadModel;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinIds;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only identity: GET /things/{id}/features/learning/properties only. */
public class DittoReadModel implements TwinReadModel {

    private final DittoHttpTransport http;
    private final DittoIdentity reader;
    private final ObjectMapper json;

    public DittoReadModel(DittoHttpTransport http, DittoIdentity reader, ObjectMapper json) {
        this.http = http;
        this.reader = reader;
        this.json = json;
    }

    @Override
    public LearningRead readLearning(ProfileId profile) {
        String path = DittoPaths.thing(TwinIds.thingId(profile)) + "/features/" + TwinIds.FEATURE + "/properties";
        DittoHttpTransport.Response r = http.send(reader, "GET", path, null, null, null);
        switch (r.status()) {
            case 200 -> {
                try {
                    JsonNode n = json.readTree(r.body());
                    if (!n.isObject()) {
                        throw new TwinStoreException(Kind.INCOMPATIBLE, "learning properties are not an object");
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    n.fields().forEachRemaining(e -> m.put(e.getKey(), json.convertValue(e.getValue(), Object.class)));
                    return new LearningRead.Found(m);
                } catch (TwinStoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new TwinStoreException(Kind.INCOMPATIBLE, "learning properties not JSON", null, e);
                }
            }
            case 404 -> {
                return new LearningRead.NotVisible();
            }
            case 403 -> throw new TwinStoreException(Kind.FORBIDDEN, "reader denied");
            default -> throw new TwinStoreException(Kind.INCOMPATIBLE, "unexpected status " + r.status());
        }
    }

    @Override
    public void verifyAuthenticated() {
        DittoHttpTransport.Response r = http.send(reader, "GET", "/whoami", null, null, null);
        if (r.status() != 200) {
            throw new TwinStoreException(Kind.INCOMPATIBLE, "whoami returned " + r.status());
        }
    }
}
