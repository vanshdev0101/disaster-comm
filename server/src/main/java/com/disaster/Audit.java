package com.disaster;

import jakarta.persistence.*;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@Entity
class ActionLog {
    @Id @GeneratedValue public Long id;
    public double time;  // epoch seconds
    public String officer, text;
}

interface ActionLogRepo extends JpaRepository<ActionLog, Long> {}

/** Who did what: every officer action is recorded and shown on the dashboard. */
@Component
@RestController
@RequestMapping("/api/log")
public class Audit {
    private final ActionLogRepo repo;

    Audit(ActionLogRepo repo) { this.repo = repo; }

    void log(String who, String text) {
        ActionLog l = new ActionLog();
        l.time = System.currentTimeMillis() / 1000.0;
        l.officer = who == null ? "system" : who;
        l.text = text;
        repo.save(l);
    }

    @GetMapping
    public List<ActionLog> recent(@RequestParam(defaultValue = "15") int limit) {
        return repo.findAll(PageRequest.of(0, Math.min(Math.max(limit, 1), 100), Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }
}
