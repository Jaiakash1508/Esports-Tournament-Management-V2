package edu.mit.league;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/** Local coursework sign-in backed by environment-configurable demo credentials. */
public final class AuthService {
    public enum Role { ADMIN, CAPTAIN, REGISTER }
    public record Login(String username, Role role, String token) {}
    private record Session(String username, Role role, long expiresAt) {}
    private static final long SESSION_LIFETIME_MS = 8 * 60 * 60 * 1000L;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Role authenticate(String username, String password) {
        if (username == null || password == null) return null;
        if (constantTimeEquals(username, "admin") && constantTimeEquals(password, configured("MIT_LEAGUE_ADMIN_PASSWORD", "admin123"))) {
            return Role.ADMIN;
        }
        if (constantTimeEquals(username, "captain") && constantTimeEquals(password, configured("MIT_LEAGUE_CAPTAIN_PASSWORD", "captain123"))) {
            return Role.CAPTAIN;
        }
        if (constantTimeEquals(username, "register") && constantTimeEquals(password, configured("MIT_LEAGUE_REGISTER_PASSWORD", "register123"))) {
            return Role.REGISTER;
        }
        return null;
    }

    public Role roleForAuthorization(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7);
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expiresAt() < System.currentTimeMillis()) { sessions.remove(token); return null; }
        return session.role();
    }

    public Login createSession(String username, String password) {
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() < System.currentTimeMillis());
        Role role = authenticate(username, password);
        if (role == null) return null;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, role, System.currentTimeMillis() + SESSION_LIFETIME_MS));
        return new Login(username, role, token);
    }

    public void revokeSession(String header) {
        if (header != null && header.startsWith("Bearer ")) sessions.remove(header.substring(7));
    }

    private String configured(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
