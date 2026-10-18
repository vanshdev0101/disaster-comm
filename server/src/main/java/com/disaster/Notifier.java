package com.disaster;

import jakarta.validation.Valid;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tells the responsible officer on Telegram when an incident opens or is assigned to them.
 * Recipients: the node's responsible officer if one is set and linked, otherwise every linked officer who is on duty.
 * Officers link their account by sending "/link CODE" to the bot (the server polls Telegram, so no public URL is needed).
 * Messages wait in an in-memory outbox and are retried while Telegram is unreachable (the Internet may be down).
 * ponytail: the outbox is lost on restart; persist it if a restart during an outage matters. Off unless a bot token is set.
 */
@Component
@RestController
@RequestMapping("/api/notify")
public class Notifier {
    record Pending(String chatId, String text, long created, int tries, long next) {}

    private final String token, api, publicUrl;
    private final AccountRepo accounts;
    private final NodeRepo nodes;
    private final Thresholds thresholds;
    private final Audit audit;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Pending> outbox = new CopyOnWriteArrayList<>();
    private final Map<String, String[]> codes = new ConcurrentHashMap<>();  // code -> {username, expiry millis}
    private volatile long offset;
    private volatile String bot = "";

    Notifier(@Value("${disaster.telegram.token:}") String token, @Value("${disaster.telegram.api:https://api.telegram.org}") String api,
             @Value("${disaster.public-url:http://localhost:8000}") String publicUrl, AccountRepo accounts, NodeRepo nodes,
             Thresholds thresholds, Audit audit, ObjectMapper json) {
        this.token = token;
        this.api = api.replaceAll("/+$", "");
        this.publicUrl = publicUrl;
        this.accounts = accounts;
        this.nodes = nodes;
        this.thresholds = thresholds;
        this.audit = audit;
        this.json = json;
    }

    boolean enabled() { return !token.isBlank(); }

    // ---------- messages ----------
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZoneId.systemDefault());

    private String nodeLabel(String node) {
        return nodes.findById(node).map(n -> n.name != null && !n.name.equals(node) ? n.name + " (" + node + ")" : node).orElse(node);
    }

    private String readingLine(Incident i, Reading r) {
        var t = thresholds.get();
        return switch (i.type) {
            case "FLOOD" -> "Water level " + r.levelCm + " cm (threshold " + t.get("level_cm").intValue() + ")";
            case "FIRE" -> "Flame sensor triggered";
            case "SMOKE" -> "Smoke reading " + r.smoke + " (threshold " + t.get("smoke").intValue() + ")";
            case "HEAT" -> "Temperature " + r.tempC + " C (threshold " + t.get("temp_c").intValue() + ")";
            case "HEAVY_RAIN" -> "Rain " + r.rain + " % (threshold " + t.get("rain").intValue() + ")";
            default -> "";
        };
    }

    private static String sev(String type) { return type.equals("FIRE") || type.equals("FLOOD") ? "CRITICAL" : "WARNING"; }

    private static String title(String type) {
        return switch (type) { case "HEAVY_RAIN" -> "Heavy rain"; case "HEAT" -> "High temperature"; default -> type.charAt(0) + type.substring(1).toLowerCase(); };
    }

    private List<Account> recipients(String node) {
        Account resp = nodes.findById(node).map(n -> n.responsible).flatMap(accounts::findById).orElse(null);
        if (resp != null && resp.telegramChatId != null) return List.of(resp);
        return accounts.findAll().stream().filter(a -> a.telegramChatId != null && (a.onDuty == null || a.onDuty)).toList();
    }

    void incidentOpened(Incident i, Reading r) {
        if (!enabled()) return;
        String text = sev(i.type) + ": " + title(i.type) + "\nNode: " + nodeLabel(i.node) + "\n" + readingLine(i, r)
                + "\nObserved: " + TIME.format(Instant.ofEpochSecond((long) i.opened)) + "\nIncident INC-" + String.format("%04d", i.id)
                + " (open, unassigned)\n" + publicUrl;
        List<Account> to = recipients(i.node);
        if (to.isEmpty()) audit.log("system", "no linked officer to notify for " + i.type + " on " + i.node);
        for (Account a : to) outbox.add(new Pending(a.telegramChatId, text, System.currentTimeMillis(), 0, 0));
    }

    void assigned(Incident i, String officer, String by) {
        if (!enabled()) return;
        accounts.findById(officer).filter(a -> a.telegramChatId != null).ifPresent(a -> outbox.add(new Pending(a.telegramChatId,
                "Assigned to you" + (by == null ? "" : " by " + by) + ": " + sev(i.type) + " " + title(i.type) + " at " + nodeLabel(i.node)
                        + "\nIncident INC-" + String.format("%04d", i.id) + "\n" + publicUrl, System.currentTimeMillis(), 0, 0)));
    }

    // ---------- Telegram I/O ----------
    private JsonNode call(String method, Map<String, Object> body) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(api + "/bot" + token + "/" + method)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("telegram " + res.statusCode());
        return json.readTree(res.body());
    }

    /** Send what is due; failures are retried with a growing delay, for up to a day. */
    @Scheduled(fixedDelay = 2000)
    public synchronized void flush() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (Pending p : List.copyOf(outbox)) {
            if (p.next() > now) continue;
            try {
                call("sendMessage", Map.of("chat_id", p.chatId(), "text", p.text(), "disable_web_page_preview", true));
                outbox.remove(p);
            } catch (Exception e) {
                outbox.remove(p);
                if (now - p.created() < 86_400_000L)
                    outbox.add(new Pending(p.chatId(), p.text(), p.created(), p.tries() + 1, now + Math.min(300_000L, 5_000L << Math.min(p.tries(), 6))));
            }
        }
    }

    /** Handles "/start" and "/link CODE" sent to the bot. */
    @Scheduled(fixedDelayString = "${disaster.telegram.poll-ms:3000}")
    public synchronized void poll() {
        if (!enabled()) return;
        try {
            if (bot.isEmpty()) bot = call("getMe", Map.of()).path("result").path("username").asString("");
            JsonNode res = call("getUpdates", Map.of("offset", offset, "timeout", 0)).path("result");
            for (JsonNode u : res) {
                offset = Math.max(offset, u.path("update_id").asLong() + 1);
                String chat = u.path("message").path("chat").path("id").asString(""), text = u.path("message").path("text").asString("").trim();
                if (chat.isEmpty()) continue;
                if (text.startsWith("/link")) reply(chat, link(chat, text.substring(5).trim()));
                else if (text.startsWith("/start")) reply(chat, "Disaster Control Station alerts. In the dashboard press Notifications, then Link Telegram, and send me /link followed by the code.");
            }
        } catch (Exception ignored) { /* Telegram unreachable: try again next poll */ }
    }

    private void reply(String chat, String text) { outbox.add(new Pending(chat, text, System.currentTimeMillis(), 0, 0)); }

    private String link(String chat, String code) {
        String[] c = codes.remove(code.toUpperCase());
        if (c == null || Long.parseLong(c[1]) < System.currentTimeMillis()) return "That code is not valid or has expired. Press Link Telegram in the dashboard for a new one.";
        Account a = accounts.findById(c[0]).orElse(null);
        if (a == null) return "That account no longer exists.";
        a.telegramChatId = chat;
        accounts.save(a);
        audit.log(a.username, "linked Telegram for alerts");
        return "Linked. You will receive alerts as " + a.username + ".";
    }

    // ---------- API for the dashboard ----------
    @GetMapping
    public Map<String, Object> status() { return Map.of("enabled", enabled(), "bot", bot, "queued", outbox.size()); }

    @PostMapping("/link")
    public Map<String, Object> newCode(@RequestAttribute(value = "officer", required = false) String who) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Telegram is not configured on this server");
        char[] a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
        StringBuilder sb = new StringBuilder();
        SecureRandom r = new SecureRandom();
        for (int k = 0; k < 6; k++) sb.append(a[r.nextInt(a.length)]);
        codes.put(sb.toString(), new String[]{who, String.valueOf(System.currentTimeMillis() + 600_000)});
        return Map.of("code", sb.toString(), "bot", bot, "minutes", 10);
    }

    record DutyIn(Boolean onDuty) {}

    @PostMapping("/duty")
    public Map<String, Object> duty(@Valid @RequestBody DutyIn in, @RequestAttribute(value = "officer", required = false) String who) {
        Account a = accounts.findById(who).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        a.onDuty = in.onDuty() == null || in.onDuty();
        accounts.save(a);
        audit.log(who, a.onDuty ? "went on duty" : "went off duty");
        return Map.of("onDuty", a.onDuty);
    }

    @PostMapping("/test")
    public Map<String, Boolean> test(@RequestAttribute(value = "officer", required = false) String who) {
        Account a = accounts.findById(who).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!enabled() || a.telegramChatId == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "link Telegram first");
        reply(a.telegramChatId, "Test alert for " + who + ": Telegram notifications are working.");
        return Map.of("queued", true);
    }
}
