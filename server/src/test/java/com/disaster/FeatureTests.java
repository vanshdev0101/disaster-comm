package com.disaster;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real HTTP against the running app: login gate, audit log, node position, history. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeatureTests {
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(String u, String p) throws Exception {
        return json.readTree(call("POST", "/api/login", "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body()).get("token").asString();
    }

    @Test
    void loginGateAuditNodesAndHistory() throws Exception {
        assertEquals(200, call("GET", "/api/state", null, null).statusCode(), "viewing is open");
        assertEquals(200, call("POST", "/api/readings", "{\"node\":\"f1\",\"boot\":1,\"seq\":1,\"temp_c\":21.5,\"level_cm\":5}", null).statusCode(),
                "devices post without a login");
        assertEquals(401, call("POST", "/api/resources", "{\"id\":1,\"status\":\"available\"}", null).statusCode());
        assertEquals(401, call("POST", "/api/login", "{\"username\":\"admin\",\"password\":\"wrong\"}", null).statusCode());
        assertEquals(401, call("POST", "/api/login", "{\"username\":\"nobody\",\"password\":\"wrong\"}", null).statusCode());

        String admin = login("admin", "admin123");
        assertEquals(200, call("POST", "/api/resources", "{\"id\":1,\"status\":\"available\"}", admin).statusCode());
        assertEquals(200, call("POST", "/api/nodes", "{\"node\":\"f1\",\"name\":\"Bridge\",\"lat\":13.08,\"lon\":80.27}", admin).statusCode());
        assertEquals(400, call("POST", "/api/nodes", "{\"node\":\"f1\",\"lat\":999,\"lon\":0}", admin).statusCode(), "bad latitude");

        JsonNode f1 = null;
        for (JsonNode n : json.readTree(call("GET", "/api/state", null, null).body()).get("nodes")) if (n.get("node").asString().equals("f1")) f1 = n;
        assertNotNull(f1);
        assertEquals("Bridge", f1.get("name").asString());
        assertEquals(13.08, f1.get("lat").asDouble());
        assertTrue(f1.get("online").asBoolean());

        assertEquals(1, json.readTree(call("GET", "/api/history?node=f1&minutes=5", null, null).body()).size());
        assertTrue(call("GET", "/api/log", null, null).body().contains("\"officer\":\"admin\""), "action was audited");

        assertEquals(200, call("POST", "/api/officers", "{\"username\":\"asha\",\"password\":\"secret1\"}", admin).statusCode());
        String asha = login("asha", "secret1");
        assertEquals(403, call("POST", "/api/officers", "{\"username\":\"x12\",\"password\":\"secret1\"}", asha).statusCode(), "officers can't add officers");
        call("POST", "/api/logout", null, asha);
        assertEquals(401, call("POST", "/api/resources", "{\"id\":1,\"status\":\"available\"}", asha).statusCode(), "logged-out token is dead");
    }

    @Test
    void thresholdsAreConfigurableAndReplayKeepsMeasurementTime() throws Exception {
        assertEquals(401, call("POST", "/api/thresholds", "{\"level_cm\":70}", null).statusCode(), "login required");
        String admin = login("admin", "admin123");
        assertEquals(400, call("POST", "/api/thresholds", "{\"bogus\":1}", admin).statusCode());
        assertEquals(200, call("POST", "/api/thresholds", "{\"level_cm\":70}", admin).statusCode());
        call("POST", "/api/readings", "{\"node\":\"t1\",\"boot\":1,\"seq\":1,\"level_cm\":60}", null);
        assertEquals(0, alertsOf("t1").size(), "60 is below the new threshold of 70");
        call("POST", "/api/thresholds", "{\"level_cm\":55}", admin);
        call("POST", "/api/readings", "{\"node\":\"t1\",\"boot\":1,\"seq\":2,\"level_cm\":60}", null);
        assertEquals("FLOOD", alertsOf("t1").get(0), "60 is above 55");
        call("POST", "/api/thresholds", "{\"level_cm\":50}", admin);  // restore the default for other tests

        // a reading replayed 10 minutes late is stored at its measurement time and does not become "latest"
        call("POST", "/api/readings", "{\"node\":\"t2\",\"boot\":1,\"seq\":2,\"temp_c\":30.0}", null);
        call("POST", "/api/readings", "{\"node\":\"t2\",\"boot\":1,\"seq\":1,\"temp_c\":11.0,\"age_ms\":600000}", null);
        for (JsonNode n : json.readTree(call("GET", "/api/state", null, null).body()).get("nodes"))
            if (n.get("node").asString().equals("t2")) assertEquals(30.0, n.get("data").get("temp_c").asDouble(), "newest reading wins");
        JsonNode h = json.readTree(call("GET", "/api/history?node=t2&minutes=30", null, null).body());
        assertEquals(2, h.size());
        assertTrue(h.get(1).get("t").asDouble() - h.get(0).get("t").asDouble() > 590, "replayed point sits ~10 min earlier");
    }

    java.util.List<String> alertsOf(String node) throws Exception {
        var out = new java.util.ArrayList<String>();
        for (JsonNode n : json.readTree(call("GET", "/api/state", null, null).body()).get("nodes"))
            if (n.get("node").asString().equals(node)) for (JsonNode a : n.get("alerts")) out.add(a.asString());
        return out;
    }
}
