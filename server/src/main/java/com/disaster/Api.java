package com.disaster;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

interface ReadingRepo extends JpaRepository<Reading, Long> {
    boolean existsByNodeAndBootAndSeq(String node, long boot, long seq);

    List<Reading> findByNodeAndRecvGreaterThanOrderByRecv(String node, double recv);

    List<Reading> findTop500ByIdGreaterThanOrderById(long id);

    long countByIdGreaterThan(long id);

    @Query("select r from Reading r where r.id in (select max(x.id) from Reading x where x.recv = "
            + "(select max(y.recv) from Reading y where y.node = x.node) group by x.node) order by r.node")
    List<Reading> latestPerNode();
}

interface ResourceRepo extends JpaRepository<Resource, Long> {}

@RestController
@RequestMapping("/api")
public class Api {
    record In(@NotBlank String node, Long boot, @NotNull Long seq,
              @JsonProperty("level_cm") Integer levelCm, Integer rain,
              @JsonProperty("temp_c") Double tempC, Integer smoke, Integer flame,
              String via, Integer rssi,
              @JsonProperty("age_ms") Long ageMs) {}  // set by the node when replaying a queued reading

    record StatusIn(@NotNull Long id, @Pattern(regexp = "available|deployed|unavailable") String status) {}

    private final ReadingRepo readings;
    private final ResourceRepo resources;
    private final Officer officer;
    private final NodeRepo nodeInfo;
    private final Audit audit;
    private final Thresholds thresholds;

    static final double OFFLINE_S = 60;  // node interval is ~10 s; a minute of silence = offline

    Api(ReadingRepo readings, ResourceRepo resources, Officer officer, NodeRepo nodeInfo, Audit audit, Thresholds thresholds) {
        this.readings = readings;
        this.resources = resources;
        this.officer = officer;
        this.nodeInfo = nodeInfo;
        this.audit = audit;
        this.thresholds = thresholds;
    }

    @Bean
    CommandLineRunner seed() {
        return a -> {
            if (resources.count() > 0) return;
            for (String n : List.of("Rescue boat", "Ambulance", "Fire engine", "Relief food kits")) {
                Resource r = new Resource();
                r.name = n;
                r.status = "available";
                resources.save(r);
            }
        };
    }

    /** Duplicates (same node/boot/seq, e.g. LoRa copy + later Wi-Fi replay) are ignored. */
    @PostMapping("/readings")
    public Map<String, Boolean> ingest(@Valid @RequestBody In in) {
        long boot = in.boot() == null ? 0 : in.boot();
        if (readings.existsByNodeAndBootAndSeq(in.node(), boot, in.seq())) return Map.of("stored", false);
        Reading r = new Reading();
        r.node = in.node(); r.boot = boot; r.seq = in.seq();
        // a replayed reading is stamped with when it was measured, not when it finally arrived
        double age = in.ageMs() == null ? 0 : Math.min(Math.max(in.ageMs(), 0), 86_400_000L) / 1000.0;
        r.recv = System.currentTimeMillis() / 1000.0 - age;
        r.via = in.via() == null ? "wifi" : in.via();
        r.rssi = in.rssi(); r.levelCm = in.levelCm(); r.rain = in.rain();
        r.tempC = in.tempC(); r.smoke = in.smoke(); r.flame = in.flame();
        readings.save(r);
        officer.raise(r);
        return Map.of("stored", true);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        double now = System.currentTimeMillis() / 1000.0;
        Map<String, NodeInfo> info = new HashMap<>();
        nodeInfo.findAll().forEach(x -> info.put(x.node, x));
        for (Reading r : readings.latestPerNode()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("level_cm", r.levelCm); data.put("rain", r.rain); data.put("temp_c", r.tempC);
            data.put("smoke", r.smoke); data.put("flame", r.flame);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("node", r.node); n.put("recv", r.recv); n.put("via", r.via); n.put("rssi", r.rssi);
            n.put("data", data); n.put("alerts", thresholds.alerts(r));
            NodeInfo ni = info.get(r.node);
            n.put("name", ni == null || ni.name == null ? r.node : ni.name);
            n.put("lat", ni == null ? null : ni.lat); n.put("lon", ni == null ? null : ni.lon);
            n.put("online", now - r.recv < OFFLINE_S);
            nodes.add(n);
        }
        return Map.of("now", now, "nodes", nodes, "incidents", officer.active(), "thresholds", thresholds.get(),
                "resources", resources.findAll(org.springframework.data.domain.Sort.by("id")));
    }

    @PostMapping("/resources")
    public Map<String, Boolean> setStatus(@Valid @RequestBody StatusIn in,
                                          @RequestAttribute(value = "officer", required = false) String who) {
        Resource r = resources.findById(in.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        r.status = in.status();
        resources.save(r);
        audit.log(who, "set " + r.name + " to " + in.status());
        return Map.of("ok", true);
    }
}
