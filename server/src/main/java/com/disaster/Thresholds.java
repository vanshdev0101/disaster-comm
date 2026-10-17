package com.disaster;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Entity
class Setting {
    @Id public String name;
    public double val;
}

interface SettingRepo extends JpaRepository<Setting, String> {}

/** Alert thresholds, editable from the dashboard (persisted, no restart). Water level is cm of water above the dry bed. */
@RestController
@RequestMapping("/api/thresholds")
public class Thresholds {
    static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("level_cm", 50.0); DEFAULTS.put("temp_c", 50.0);
        DEFAULTS.put("rain", 80.0); DEFAULTS.put("smoke", 2000.0);
    }

    private final SettingRepo repo;
    private final Audit audit;
    private volatile Map<String, Double> cur;  // cached: read on every incoming reading

    Thresholds(SettingRepo repo, Audit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @GetMapping
    public synchronized Map<String, Double> get() {
        if (cur == null) {
            Map<String, Double> m = new LinkedHashMap<>(DEFAULTS);
            repo.findAll().forEach(s -> { if (m.containsKey(s.name)) m.put(s.name, s.val); });
            cur = m;
        }
        return cur;
    }

    @PostMapping
    public synchronized Map<String, Double> set(@RequestBody Map<String, Double> in,
                                                @RequestAttribute(value = "officer", required = false) String who) {
        Map<String, Double> m = new LinkedHashMap<>(get());
        StringBuilder changed = new StringBuilder();
        for (var e : in.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey()) || e.getValue() == null || e.getValue() < 0 || e.getValue() > 100000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad threshold " + e.getKey());
            if (!e.getValue().equals(m.get(e.getKey())))
                changed.append(changed.length() > 0 ? ", " : "").append(e.getKey()).append(' ')
                        .append(m.get(e.getKey()).intValue()).append("->").append(e.getValue().intValue());
            m.put(e.getKey(), e.getValue());
        }
        m.forEach((k, v) -> { Setting s = new Setting(); s.name = k; s.val = v; repo.save(s); });
        cur = m;
        if (changed.length() > 0) audit.log(who, "changed alert thresholds: " + changed);
        return m;
    }

    List<String> alerts(Reading r) {
        Map<String, Double> t = get();
        List<String> a = new ArrayList<>();
        if (r.levelCm != null && r.levelCm >= t.get("level_cm")) a.add("FLOOD");
        if (r.flame != null && r.flame != 0) a.add("FIRE");
        if (r.smoke != null && r.smoke >= t.get("smoke")) a.add("SMOKE");
        if (r.tempC != null && r.tempC >= t.get("temp_c")) a.add("HEAT");
        if (r.rain != null && r.rain >= t.get("rain")) a.add("HEAVY_RAIN");
        return a;
    }
}
