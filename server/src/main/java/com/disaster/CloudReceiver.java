package com.disaster;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Entity
class CloudSnapshot {
    @Id public String station;
    @Column(length = 1_000_000) public String json;  // latest incidents/resources/nodes/log of that station, as sent
    public double receivedAt;
}

interface CloudSnapshotRepo extends JpaRepository<CloudSnapshot, String> {}

/**
 * The "cloud server" end: a control station uploads its stored readings and a snapshot here once the Internet is back.
 * Run the same app on a hosted machine with DISASTER_CLOUD_RECEIVER_KEY set; with no key the endpoint is disabled.
 * ponytail: one snapshot per station but readings are not tagged by station, so one station per cloud instance for now.
 */
@RestController
@RequestMapping("/api/cloud")
public class CloudReceiver {
    record R(String node, long boot, long seq, double recv, String via, Integer rssi,
             Integer level_cm, Integer rain, Double temp_c, Integer smoke, Integer flame) {}
    record Batch(@NotBlank String station, List<R> readings, Map<String, Object> snapshot) {}

    private final ReadingRepo readings;
    private final CloudSnapshotRepo snapshots;
    private final ObjectMapper json;
    private final String key;

    CloudReceiver(ReadingRepo readings, CloudSnapshotRepo snapshots, ObjectMapper json,
                  @Value("${disaster.cloud.receiver-key:}") String key) {
        this.readings = readings;
        this.snapshots = snapshots;
        this.json = json;
        this.key = key;
    }

    @PostMapping("/ingest")
    @org.springframework.transaction.annotation.Transactional  // one commit per batch: a commit per row is far too slow on a remote database
    public Map<String, Object> ingest(@RequestHeader(value = "X-Api-Key", required = false) String k, @Valid @RequestBody Batch b) {
        if (key.isBlank() || !MessageDigest.isEqual(key.getBytes(), (k == null ? "" : k).getBytes()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad or missing API key");
        int added = 0, dup = 0;
        for (R r : b.readings() == null ? List.<R>of() : b.readings()) {
            if (readings.existsByNodeAndBootAndSeq(r.node(), r.boot(), r.seq())) { dup++; continue; }
            Reading x = new Reading();  // stored as measured: no alert logic here, the station already did that
            x.node = r.node(); x.boot = r.boot(); x.seq = r.seq(); x.recv = r.recv(); x.via = r.via(); x.rssi = r.rssi();
            x.levelCm = r.level_cm(); x.rain = r.rain(); x.tempC = r.temp_c(); x.smoke = r.smoke(); x.flame = r.flame();
            readings.save(x);
            added++;
        }
        if (b.snapshot() != null) {
            CloudSnapshot s = new CloudSnapshot();
            s.station = b.station();
            s.json = json.writeValueAsString(b.snapshot());
            s.receivedAt = System.currentTimeMillis() / 1000.0;
            snapshots.save(s);
        }
        return Map.of("accepted", added, "duplicates", dup);
    }

    /** Read-only view for the cloud page. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<Map<String, Object>> stations = new ArrayList<>();
        for (CloudSnapshot s : snapshots.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("station", s.station); m.put("receivedAt", s.receivedAt);
            m.put("snapshot", json.readValue(s.json, Map.class));
            stations.add(m);
        }
        return Map.of("now", System.currentTimeMillis() / 1000.0, "readings", readings.count(), "stations", stations);
    }
}
