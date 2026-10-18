package com.disaster;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/** Both ends of cloud sync: the receiving endpoint (over real HTTP) and the uploader (against a stand-in cloud that can fail). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "disaster.cloud.receiver-key=testkey")
class CloudTests {
    @LocalServerPort int port;
    @Autowired ReadingRepo readings;
    @Autowired SettingRepo settings;
    @Autowired Api api;
    @Autowired Officer officer;
    @Autowired Audit audit;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> post(String path, String body, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (key != null) b.header("X-Api-Key", key);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void receiverNeedsKeyStoresOnceAndKeepsMeasurementTime() throws Exception {
        double measured = System.currentTimeMillis() / 1000.0 - 600;
        String body = "{\"station\":\"s1\",\"readings\":[{\"node\":\"cl1\",\"boot\":1,\"seq\":1,\"recv\":" + measured
                + ",\"via\":\"lora\",\"level_cm\":12,\"temp_c\":30.5}],\"snapshot\":{\"nodes\":[],\"history\":[]}}";
        assertEquals(403, post("/api/cloud/ingest", body, null).statusCode(), "no key");
        assertEquals(403, post("/api/cloud/ingest", body, "wrong").statusCode(), "wrong key");
        assertEquals(200, post("/api/cloud/ingest", body, "testkey").statusCode());
        assertTrue(post("/api/cloud/ingest", body, "testkey").body().contains("\"duplicates\":1"), "repeat is not stored twice");
        var all = readings.findByNodeAndRecvGreaterThanOrderByRecv("cl1", 0);
        assertEquals(1, all.size());
        assertEquals(measured, all.get(0).recv, 0.001, "stored at the time it was measured, not when uploaded");
        var summary = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cloud/summary")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(summary.body().contains("\"station\":\"s1\""));
    }

    @Test
    void uploaderKeepsDataPendingUntilTheCloudConfirmsThenStopsResending() throws Exception {
        AtomicInteger status = new AtomicInteger(500), calls = new AtomicInteger();
        StringBuilder lastBody = new StringBuilder();
        AtomicReference<String> keySeen = new AtomicReference<>();
        HttpServer cloud = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        cloud.createContext("/api/cloud/ingest", ex -> {
            calls.incrementAndGet();
            lastBody.setLength(0); lastBody.append(new String(ex.getRequestBody().readAllBytes()));
            keySeen.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] out = "{\"accepted\":1}".getBytes();
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out); ex.close();
        });
        cloud.start();
        try {
            CloudSync sync = new CloudSync("http://localhost:" + cloud.getAddress().getPort(), "k1", "st", readings, settings, api, officer, audit, json);
            api.ingest(new Api.In("up1", 1L, 1L, 5, null, null, null, 0, null, null, null));
            api.ingest(new Api.In("up1", 1L, 2L, 6, null, null, null, 0, null, null, null));

            sync.tick();  // cloud is "down" (500)
            assertEquals(false, sync.status().get("online"));
            assertTrue((long) sync.status().get("pending") >= 2, "nothing is marked uploaded after a failure");

            status.set(200);
            sync.tick();  // cloud is back
            assertEquals(true, sync.status().get("online"));
            assertEquals(0L, sync.status().get("pending"), "all confirmed");
            assertEquals("k1", keySeen.get(), "API key is sent");
            assertTrue(lastBody.toString().contains("\"node\":\"up1\""), "the readings were in the upload");

            int before = calls.get();
            sync.tick();  // nothing new: no pointless re-upload
            assertEquals(before, calls.get(), "confirmed readings are not sent again");
        } finally { cloud.stop(0); }
    }
}
