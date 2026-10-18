package com.disaster;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Officer notification against a stand-in Telegram server: who is told, assignment, and linking by "/link CODE". */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotifyTests {
    static final List<String> sent = new CopyOnWriteArrayList<>();  // sendMessage bodies
    static final AtomicReference<String> updates = new AtomicReference<>("{\"ok\":true,\"result\":[]}");
    static HttpServer telegram;

    static {
        try {
            telegram = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            telegram.createContext("/botTEST/", ex -> {
                String m = ex.getRequestURI().getPath().substring("/botTEST/".length());
                String body = new String(ex.getRequestBody().readAllBytes());
                String out = switch (m) {
                    case "sendMessage" -> { sent.add(body); yield "{\"ok\":true}"; }
                    case "getMe" -> "{\"ok\":true,\"result\":{\"username\":\"test_bot\"}}";
                    default -> updates.getAndSet("{\"ok\":true,\"result\":[]}");  // getUpdates: hand each update over once
                };
                ex.sendResponseHeaders(200, out.length());
                ex.getResponseBody().write(out.getBytes()); ex.close();
            });
            telegram.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("disaster.telegram.token", () -> "TEST");
        r.add("disaster.telegram.api", () -> "http://localhost:" + telegram.getAddress().getPort());
        r.add("disaster.telegram.poll-ms", () -> "3600000");  // the test drives poll() itself
    }

    @LocalServerPort int port;
    @Autowired Api api;
    @Autowired Notifier notifier;
    @Autowired AccountRepo accounts;
    @Autowired NodeRepo nodes;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(String u, String p) throws Exception {
        return json.readTree(call("POST", "/api/login", "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body()).get("token").asString();
    }

    List<String> textsTo(String chat) {
        List<String> out = new ArrayList<>();
        for (String b : sent) { JsonNode n = json.readTree(b); if (n.get("chat_id").asString().equals(chat)) out.add(n.get("text").asString()); }
        return out;
    }

    Account officer(String name, String chat, boolean duty) {
        Account a = new Account(); a.username = name; a.telegramChatId = chat; a.onDuty = duty; a.role = "officer"; return accounts.save(a);
    }

    Long incidentId(String node, String type) throws Exception {
        for (JsonNode i : json.readTree(call("GET", "/api/state", null, null).body()).get("incidents"))
            if (i.get("node").asString().equals(node) && i.get("type").asString().equals(type)) return i.get("id").asLong();
        throw new AssertionError("no " + type + " incident on " + node);
    }

    @Test
    void onDutyOfficersAreToldAndTheNodesOwnerWinsWhenSet() throws Exception {
        officer("asha", "111", true);
        officer("ravi", "222", false);  // off duty
        api.ingest(new Api.In("nt1", 1L, 1L, 60, null, null, null, 0, null, null, null));
        notifier.flush();
        assertTrue(textsTo("111").stream().anyMatch(t -> t.contains("CRITICAL: Flood") && t.contains("nt1") && t.contains("60 cm")), "on-duty officer is told");
        assertTrue(textsTo("222").isEmpty(), "off-duty officer is not");

        NodeInfo ni = new NodeInfo(); ni.node = "nt2"; ni.responsible = "ravi"; nodes.save(ni);
        api.ingest(new Api.In("nt2", 1L, 1L, 0, null, null, null, 1, null, null, null));  // flame
        notifier.flush();
        assertTrue(textsTo("222").stream().anyMatch(t -> t.contains("CRITICAL: Fire")), "the node's responsible officer is told, even off duty");
        assertTrue(textsTo("111").stream().noneMatch(t -> t.contains("nt2")), "and only that officer");
    }

    @Test
    void acknowledgingClaimsTheIncidentAssigningNotifiesAndLinkingWorks() throws Exception {
        officer("asha2", "444", true);
        String admin = login("admin", "admin123");
        api.ingest(new Api.In("nt3", 1L, 1L, 70, null, null, null, 0, null, null, null));
        Long id = incidentId("nt3", "FLOOD");
        assertEquals(200, call("POST", "/api/incidents/" + id + "/ack", "{}", admin).statusCode());
        assertTrue(call("GET", "/api/state", null, null).body().contains("\"assignedTo\":\"admin\""), "whoever acknowledges becomes responsible");

        assertEquals(400, call("POST", "/api/incidents/" + id + "/assign", "{\"officer\":\"nobody\"}", admin).statusCode());
        assertEquals(401, call("POST", "/api/incidents/" + id + "/assign", "{\"officer\":\"asha2\"}", null).statusCode());
        assertEquals(200, call("POST", "/api/incidents/" + id + "/assign", "{\"officer\":\"asha2\"}", admin).statusCode());
        notifier.flush();
        assertTrue(textsTo("444").stream().anyMatch(t -> t.startsWith("Assigned to you by admin")), "the assignee is told");

        assertEquals(401, call("GET", "/api/officers", null, null).statusCode(), "the officer list is not public");
        assertEquals(200, call("POST", "/api/officers", "{\"username\":\"newbie\",\"password\":\"secret1\"}", admin).statusCode());
        String newbie = login("newbie", "secret1");
        String code = json.readTree(call("POST", "/api/notify/link", null, newbie).body()).get("code").asString();
        updates.set("{\"ok\":true,\"result\":[{\"update_id\":9,\"message\":{\"chat\":{\"id\":333},\"text\":\"/link " + code + "\"}}]}");
        notifier.poll();
        assertEquals("333", accounts.findById("newbie").orElseThrow().telegramChatId, "linked by the code");
        notifier.flush();
        assertTrue(textsTo("333").stream().anyMatch(t -> t.startsWith("Linked")), "the bot confirms");

        updates.set("{\"ok\":true,\"result\":[{\"update_id\":10,\"message\":{\"chat\":{\"id\":999},\"text\":\"/link " + code + "\"}}]}");
        notifier.poll();
        assertEquals("333", accounts.findById("newbie").orElseThrow().telegramChatId, "a code works once");
    }
}
