package com.disaster;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.*;

@Entity
class NodeInfo {
    @Id public String node;
    public String name;
    public Double lat, lon;
}

interface NodeRepo extends JpaRepository<NodeInfo, String> {}

/** Node names/map positions (set by officers) and sensor history for the dashboard charts. */
@RestController
@RequestMapping("/api")
public class Nodes {
    record NodeIn(@NotBlank String node, String name,
                  @DecimalMin("-90") @DecimalMax("90") Double lat,
                  @DecimalMin("-180") @DecimalMax("180") Double lon) {}

    private final NodeRepo nodes;
    private final ReadingRepo readings;
    private final Audit audit;

    Nodes(NodeRepo nodes, ReadingRepo readings, Audit audit) {
        this.nodes = nodes;
        this.readings = readings;
        this.audit = audit;
    }

    @PostMapping("/nodes")
    public Map<String, Boolean> save(@Valid @RequestBody NodeIn in,
                                     @RequestAttribute(value = "officer", required = false) String who) {
        NodeInfo n = nodes.findById(in.node()).orElseGet(() -> { NodeInfo x = new NodeInfo(); x.node = in.node(); return x; });
        if (in.name() != null && !in.name().isBlank()) n.name = in.name().trim();
        if (in.lat() != null && in.lon() != null) { n.lat = in.lat(); n.lon = in.lon(); }
        nodes.save(n);
        audit.log(who, "updated node " + in.node());
        return Map.of("ok", true);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String node, @RequestParam(defaultValue = "30") int minutes) {
        double since = System.currentTimeMillis() / 1000.0 - Math.min(Math.max(minutes, 1), 1440) * 60;
        List<Reading> all = readings.findByNodeAndRecvGreaterThanOrderByRecv(node, since);
        int step = Math.max(1, all.size() / 300);  // keep the chart payload small
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += step) {
            Reading r = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", r.recv); m.put("seq", r.seq); m.put("level_cm", r.levelCm); m.put("rain", r.rain);
            m.put("temp_c", r.tempC); m.put("via", r.via);
            out.add(m);
        }
        return out;
    }
}
