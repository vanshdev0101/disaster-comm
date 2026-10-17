package com.disaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApiTests {
    @Autowired Api api;

    Api.In in(String node, long boot, long seq, Integer level, Integer flame, String via, Integer rssi) {
        return new Api.In(node, boot, seq, level, null, null, null, flame, via, rssi);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> node(String name) {
        return ((List<Map<String, Object>>) api.state().get("nodes")).stream()
                .filter(n -> n.get("node").equals(name)).findFirst().orElseThrow();
    }

    @Test
    void ingestDedupAlertsAndResources() {
        assertTrue(api.ingest(in("n1", 1, 1, 70, 1, null, null)).get("stored"));
        assertFalse(api.ingest(in("n1", 1, 1, 70, 1, null, null)).get("stored"), "dup must be ignored");
        assertTrue(api.ingest(in("n1", 2, 1, 10, 0, null, null)).get("stored"), "new boot id must be accepted");
        assertEquals(List.of(), node("n1").get("alerts"));

        api.ingest(in("n2", 1, 1, 70, 1, "lora", -90));
        assertEquals(List.of("FLOOD", "FIRE"), node("n2").get("alerts"));
        assertEquals("lora", node("n2").get("via"));

        api.setStatus(new Api.StatusIn(1L, "deployed"));
        @SuppressWarnings("unchecked")
        List<Resource> res = (List<Resource>) api.state().get("resources");
        assertEquals("deployed", res.get(0).status);
        assertEquals(4, res.size());
        api.setStatus(new Api.StatusIn(1L, "available"));  // shared in-memory DB: leave it clean
    }

    @Autowired Officer officer;

    @SuppressWarnings("unchecked")
    Map<String, Object> incident(String node) {
        return ((List<Map<String, Object>>) api.state().get("incidents")).stream()
                .filter(i -> i.get("node").equals(node)).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void officerWorkflow() {
        api.ingest(in("o1", 1, 1, 70, 0, null, null));
        api.ingest(in("o1", 1, 2, 80, 0, null, null));  // still flooding: no second incident
        Map<String, Object> inc = incident("o1");
        assertEquals(1, ((List<?>) api.state().get("incidents")).stream()
                .filter(i -> ((Map<String, Object>) i).get("node").equals("o1")).count());
        assertEquals("FLOOD", inc.get("type"));
        assertEquals("open", inc.get("status"));
        List<Map<String, Object>> sug = (List<Map<String, Object>>) inc.get("suggested");
        assertEquals(List.of("Rescue boat", "Relief food kits"), sug.stream().map(m -> m.get("name")).toList());

        Long id = (Long) inc.get("id"), boat = (Long) sug.get(0).get("id");
        officer.allocate(id, new Officer.AllocIn(boat));
        assertEquals("acknowledged", incident("o1").get("status"));
        assertEquals(1, ((List<?>) incident("o1").get("assigned")).size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> officer.allocate(id, new Officer.AllocIn(boat)), "already deployed");

        officer.resolve(id);
        assertTrue(((List<?>) api.state().get("incidents")).stream()
                .noneMatch(i -> ((Map<String, Object>) i).get("node").equals("o1")));
        assertEquals("available", ((List<Resource>) api.state().get("resources")).stream()
                .filter(r -> r.id.equals(boat)).findFirst().orElseThrow().status);
        api.ingest(in("o1", 1, 3, 90, 0, null, null));  // new alarm after resolve opens a fresh incident
        assertEquals("open", incident("o1").get("status"));
    }
}
