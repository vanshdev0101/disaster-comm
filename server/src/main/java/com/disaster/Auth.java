package com.disaster;

import jakarta.persistence.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Entity
class Account {
    @Id public String username;
    public String salt, hash, role;  // role: admin | officer
    public String telegramChatId;  // set by linking from Telegram, see Notifier
    public Boolean onDuty;  // null counts as on duty
}

interface AccountRepo extends JpaRepository<Account, String> {}

/**
 * Officer login. Viewing (GET) is open; every other /api call except sensor ingest and login needs a bearer token.
 * ponytail: tokens live in memory until logout/restart; swap for Spring Security + sessions if this leaves the LAN.
 */
@RestController
@RequestMapping("/api")
public class Auth implements WebMvcConfigurer {
    record LoginIn(@NotBlank String username, @NotBlank String password) {}
    record NewOfficer(@NotBlank @Size(min = 3, max = 32) String username,
                      @NotBlank @Size(min = 6) String password, String role) {}

    private final AccountRepo accounts;
    private final Map<String, Account> sessions = new ConcurrentHashMap<>();
    private final String adminPassword;

    Auth(AccountRepo accounts, @Value("${disaster.admin.password:admin123}") String adminPassword) {
        this.accounts = accounts;
        this.adminPassword = adminPassword;
    }

    static String hash(String pw, String salt) {
        try {
            var spec = new PBEKeySpec(pw.toCharArray(), HexFormat.of().parseHex(salt), 120_000, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    private Account make(String user, String pw, String role) {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        Account a = new Account();
        a.username = user; a.salt = HexFormat.of().formatHex(s); a.hash = hash(pw, a.salt); a.role = role;
        return a;
    }

    @Bean
    CommandLineRunner seedAdmin() {
        return a -> {
            if (accounts.count() > 0) return;
            accounts.save(make("admin", adminPassword, "admin"));
            if ("admin123".equals(adminPassword))
                System.err.println("WARNING: default admin password 'admin123' in use. Set disaster.admin.password.");
        };
    }

    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody LoginIn in) {
        Account a = accounts.findById(in.username()).orElse(null);
        // always hash, so a wrong username and a wrong password take the same time
        String h = hash(in.password(), a == null ? "00" : a.salt);
        if (a == null || !MessageDigest.isEqual(h.getBytes(), a.hash.getBytes()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad username or password");
        String token = UUID.randomUUID().toString();
        sessions.put(token, a);
        return Map.of("token", token, "username", a.username, "role", a.role);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest req) {
        String t = bearer(req);
        if (t != null) sessions.remove(t);
        return Map.of("ok", true);
    }

    /** Who can be assigned and notified. Names are only shown to signed-in officers. */
    @GetMapping("/officers")
    public List<Map<String, Object>> officers(HttpServletRequest req) {
        if (who(req) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        return accounts.findAll().stream().sorted(Comparator.comparing((Account a) -> a.username)).map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", a.username); m.put("role", a.role);
            m.put("linked", a.telegramChatId != null); m.put("onDuty", a.onDuty == null || a.onDuty);
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/officers")
    public Map<String, Boolean> addOfficer(@Valid @RequestBody NewOfficer in,
                                           @RequestAttribute(value = "role", required = false) String role) {
        if (!"admin".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        if (accounts.existsById(in.username())) throw new ResponseStatusException(HttpStatus.CONFLICT, "username taken");
        accounts.save(make(in.username(), in.password(), "admin".equals(in.role()) ? "admin" : "officer"));
        return Map.of("ok", true);
    }

    static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }

    /** Returns the logged-in account for this request, or null. */
    Account who(HttpServletRequest req) {
        String t = bearer(req);
        return t == null ? null : sessions.get(t);
    }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) throws Exception {
                String m = req.getMethod(), p = req.getRequestURI();
                if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")
                        || p.equals("/api/readings") || p.equals("/api/login")
                        || p.equals("/api/cloud/ingest")) return true;  // devices and the cloud uplink use keys, not officer logins
                Account a = who(req);
                if (a == null) { res.sendError(401, "login required"); return false; }
                req.setAttribute("officer", a.username);
                req.setAttribute("role", a.role);
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
