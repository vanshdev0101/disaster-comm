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
    private final Audit audit;
    private final Thresholds thresholds;
    private final Notifier notifier;
    private final AccountRepo accounts;

    Officer(IncidentRepo incidents, ResourceRepo resources, Audit audit, Thresholds thresholds, Notifier notifier, AccountRepo accounts) {
        this.notifier = notifier;
        this.accounts = accounts;
        this.incidents = incidents;
        this.resources = resources;
        this.audit = audit;
        this.thresholds = thresholds;
    }

    /** One unresolved incident per node+alert type, so a sensor that stays in alarm doesn't spam. */
    void raise(Reading r) {
        for (String type : thresholds.alerts(r)) {
            if (incidents.existsByNodeAndTypeAndStatusNot(r.node, type, "resolved")) continue;
            Incident i = new Incident();
            i.node = r.node; i.type = type; i.opened = r.recv; i.status = "open";
            incidents.save(i);
            notifier.incidentOpened(i, r);
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
        m.put("opened", i.opened); m.put("status", i.status); m.put("assignedTo", i.assignedTo);
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

    /** Whoever first acts on an incident becomes the responsible officer, unless one was already assigned. */
    private void claim(Incident i, String who) {
        if (i.assignedTo == null && who != null) { i.assignedTo = who; i.assignedAt = System.currentTimeMillis() / 1000.0; }
    }

    record AssignIn(@jakarta.validation.constraints.NotBlank String officer) {}

    @PostMapping("/{id}/assign")
    public Map<String, Boolean> assign(@PathVariable Long id, @Valid @RequestBody AssignIn in,
                                       @RequestAttribute(value = "officer", required = false) String who) {
        Incident i = get(id, true);
        if (!accounts.existsById(in.officer())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such officer");
        i.assignedTo = in.officer(); i.assignedAt = System.currentTimeMillis() / 1000.0;
        incidents.save(i);
        audit.log(who, "assigned " + i.type + " on " + i.node + " to " + in.officer());
        if (!in.officer().equals(who)) notifier.assigned(i, in.officer(), who);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/ack")
    public Map<String, Boolean> ack(@PathVariable Long id,
                                    @RequestAttribute(value = "officer", required = false) String who) {
        Incident i = get(id, true);
        if ("open".equals(i.status)) { i.status = "acknowledged"; claim(i, who); incidents.save(i); audit.log(who, "acknowledged " + i.type + " on " + i.node); }
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/allocate")
    public Map<String, Boolean> allocate(@PathVariable Long id, @Valid @RequestBody AllocIn in,
                                         @RequestAttribute(value = "officer", required = false) String who) {
        Incident i = get(id, true);
        Resource r = resources.findById(in.resourceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"available".equals(r.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resource not available");
        r.status = "deployed"; r.incident = i.id;
        resources.save(r);
        audit.log(who, "sent " + r.name + " to " + i.type + " on " + i.node);
        if ("open".equals(i.status)) { i.status = "acknowledged"; claim(i, who); incidents.save(i); }  // acting on it implies ack
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/resolve")
    public Map<String, Boolean> resolve(@PathVariable Long id,
                                        @RequestAttribute(value = "officer", required = false) String who) {
        Incident i = get(id, true);
        for (Resource r : resources.findAll())
            if (i.id.equals(r.incident)) { r.status = "available"; r.incident = null; resources.save(r); }
        i.status = "resolved";
        incidents.save(i);
        audit.log(who, "resolved " + i.type + " on " + i.node);
        return Map.of("ok", true);
    }
}
