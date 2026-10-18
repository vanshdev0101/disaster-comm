package com.disaster;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Store-and-forward to the cloud: readings stay in the local database, and every 10 s we try to upload the ones the cloud
 * has not confirmed yet, plus a snapshot of incidents/resources. Nothing is marked uploaded until the cloud answers 200,
 * so a dead Internet link just means "pending" until it is back. The cloud ignores repeats, so retries are safe.
 * Off unless disaster.cloud.url is set.
 */
@Component
@RestController
@RequestMapping("/api/sync")
public class CloudSync {
    static final String MARK = "cloud.reading";  // highest local reading id the cloud has confirmed
    static final int BATCH = 500;

    private final String url, key, station;
    private final ReadingRepo readings;
    private final SettingRepo settings;
    private final Api api;
    private final Officer officer;
    private final Audit audit;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private volatile boolean online;
    private volatile double lastOk;
    private volatile String error = "";
    private String lastSnapshot = "";
    private long lastSnapshotAt;

    CloudSync(@Value("${disaster.cloud.url:}") String url, @Value("${disaster.cloud.key:}") String key,
              @Value("${disaster.station:station1}") String station, ReadingRepo readings, SettingRepo settings,
              Api api, Officer officer, Audit audit, ObjectMapper json) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.station = station;
        this.readings = readings;
        this.settings = settings;
        this.api = api;
        this.officer = officer;
        this.audit = audit;
        this.json = json;
    }

    private long mark() { return settings.findById(MARK).map(s -> (long) s.val).orElse(0L); }

    private void setMark(long id) { Setting s = new Setting(); s.name = MARK; s.val = id; settings.save(s); }

    private Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>(api.state());
        s.remove("now");  // changes every call, would make every snapshot look new
        s.put("history", officer.history().stream().limit(100).collect(Collectors.toList()));
        s.put("log", audit.recent(30));
        return s;
    }

    @Scheduled(fixedDelay = 10_000)
    public synchronized void tick() {
        if (url.isBlank()) return;
        try {
            long mark = mark();
            for (int round = 0; round < 20; round++) {  // catch up a long backlog quickly, but bounded
                List<Reading> batch = readings.findTop500ByIdGreaterThanOrderById(mark);
                Map<String, Object> snap = snapshot();
                String snapJson = json.writeValueAsString(snap);
                boolean snapChanged = !snapJson.equals(lastSnapshot) || System.currentTimeMillis() - lastSnapshotAt > 60_000;
                if (batch.isEmpty() && !snapChanged) break;
                var rows = batch.stream().map(r -> new CloudReceiver.R(r.node, r.boot, r.seq, r.recv, r.via, r.rssi,
                        r.levelCm, r.rain, r.tempC, r.smoke, r.flame)).toList();
                String body = json.writeValueAsString(new CloudReceiver.Batch(station, rows, snap));
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url + "/api/cloud/ingest"))
                        .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("X-Api-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new IllegalStateException("cloud answered " + res.statusCode());
                if (!batch.isEmpty()) { mark = batch.get(batch.size() - 1).id; setMark(mark); }
                lastSnapshot = snapJson;
                lastSnapshotAt = System.currentTimeMillis();
                online = true; lastOk = System.currentTimeMillis() / 1000.0; error = "";
                if (batch.size() < BATCH) break;
            }
        } catch (Exception e) {
            online = false;
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** Shown on the dashboard: is it configured, is the cloud reachable, how many readings are still waiting. */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", !url.isBlank());
        m.put("online", online);
        m.put("pending", url.isBlank() ? 0 : readings.countByIdGreaterThan(mark()));
        m.put("lastSync", lastOk);
        m.put("error", error);
        m.put("target", url.isBlank() ? "" : URI.create(url).getHost());
        return m;
    }
}
