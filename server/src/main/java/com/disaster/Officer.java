package com.disaster;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

interface IncidentRepo extends JpaRepository<Incident, Long> {
    boolean existsByNodeAndTypeAndStatusNot(String node, String type, String status);
    List<Incident> findByStatusNotOrderByIdDesc(String status);
}

/** Officer workflow: alert -> incident -> acknowledge -> allocate resources -> resolve. */
@RestController
@RequestMapping("/api/incidents")
public class Officer {
    record AllocIn(@NotNull Long resourceId) {}

    // ponytail: static rule table, make it editable data if officers need to tune it
    static final Map<String, List<String>> NEEDS = Map.of(
            "FLOOD", List.of("Rescue boat", "Relief food kits"),
            "FIRE", List.of("Fire engine", "Ambulance"),
            "SMOKE", List.of("Fire engine"),
            "HEAT", List.of("Ambulance"),
            "HEAVY_RAIN", List.of());

    private final IncidentRepo incidents;
    private final ResourceRepo resources;

    Officer(IncidentRepo incidents, ResourceRepo resources) {
        this.incidents = incidents;
        this.resources = resources;
    }

    /** One unresolved incident per node+alert type, so a sensor that stays in alarm doesn't spam. */
    void raise(Reading r) {
        for (String type : Api.alerts(r)) {
            if (incidents.existsByNodeAndTypeAndStatusNot(r.node, type, "resolved")) continue;
            Incident i = new Incident();
            i.node = r.node; i.type = type; i.opened = r.recv; i.status = "open";
            incidents.save(i);
        }
    }

    private Map<String, Object> view(Incident i, List<Resource> all) {
        List<Map<String, Object>> assigned = new ArrayList<>(), suggested = new ArrayList<>();
        for (Resource r : all) {
            if (i.id.equals(r.incident)) assigned.add(Map.of("id", r.id, "name", r.name));
            else if ("available".equals(r.status) && NEEDS.get(i.type).contains(r.name))
                suggested.add(Map.of("id", r.id, "name", r.name));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id); m.put("node", i.node); m.put("type", i.type);
        m.put("opened", i.opened); m.put("status", i.status);
        m.put("assigned", assigned); m.put("suggested", suggested);
        return m;
    }

    List<Map<String, Object>> active() {
        List<Resource> all = resources.findAll();
        return incidents.findByStatusNotOrderByIdDesc("resolved").stream().map(i -> view(i, all)).toList();
    }

    @GetMapping
    public List<Map<String, Object>> history() {
        List<Resource> all = resources.findAll();
        return incidents.findAll().stream().sorted(Comparator.comparing((Incident i) -> i.id).reversed())
                .map(i -> view(i, all)).toList();
    }

    private Incident get(Long id, boolean mustBeActive) {
        Incident i = incidents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (mustBeActive && "resolved".equals(i.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "incident already resolved");
        return i;
    }

    @PostMapping("/{id}/ack")
    public Map<String, Boolean> ack(@PathVariable Long id) {
        Incident i = get(id, true);
        if ("open".equals(i.status)) { i.status = "acknowledged"; incidents.save(i); }
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/allocate")
    public Map<String, Boolean> allocate(@PathVariable Long id, @Valid @RequestBody AllocIn in) {
        Incident i = get(id, true);
        Resource r = resources.findById(in.resourceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"available".equals(r.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resource not available");
        r.status = "deployed"; r.incident = i.id;
        resources.save(r);
        if ("open".equals(i.status)) { i.status = "acknowledged"; incidents.save(i); }  // acting on it implies ack
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/resolve")
    public Map<String, Boolean> resolve(@PathVariable Long id) {
        Incident i = get(id, true);
        for (Resource r : resources.findAll())
            if (i.id.equals(r.incident)) { r.status = "available"; r.incident = null; resources.save(r); }
        i.status = "resolved";
        incidents.save(i);
        return Map.of("ok", true);
    }
}
