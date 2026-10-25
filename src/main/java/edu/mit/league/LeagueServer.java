package edu.mit.league;

import com.google.gson.JsonParseException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves the web UI and a small same-origin JSON API for the coursework demo. */
public final class LeagueServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final Gson GSON = new Gson();
    private static final AuthService AUTH = new AuthService();

    private LeagueServer() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path databaseFile = projectRoot.resolve("data").resolve("mit-league.sqlite");
        Database database = new Database(databaseFile);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(8, task -> {
            Thread thread = new Thread(task, "mit-league-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, database, projectRoot));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdown();
        }));
        System.out.println("MIT League is running at http://localhost:" + port);
        System.out.println("Tournament database: " + database.engine());
        new CountDownLatch(1).await();
    }

    private static void handle(HttpExchange exchange, Database database, Path projectRoot) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/favicon.ico")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(exchange, path, method, database);
                return;
            }
            if (!method.equals("GET")) {
                sendJson(exchange, 405, Map.of("error", "Only GET is supported for website files."));
                return;
            }
            String contentType;
            String fileName;
            if (path.equals("/styles.css")) {
                contentType = "text/css; charset=utf-8";
                fileName = "styles.css";
            } else if (path.equals("/app.js")) {
                contentType = "text/javascript; charset=utf-8";
                fileName = "app.js";
            } else if (path.equals("/") || path.equals("/index.html")) {
                contentType = "text/html; charset=utf-8";
                fileName = "index.html";
            } else {
                sendJson(exchange, 404, Map.of("error", "Page not found."));
                return;
            }
            Path asset = projectRoot.resolve(fileName).normalize();
            if (!asset.startsWith(projectRoot) || !Files.isRegularFile(asset)) {
                sendJson(exchange, 404, Map.of("error", fileName + " was not found in the project folder."));
                return;
            }
            byte[] body = Files.readAllBytes(asset);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IllegalArgumentException | JsonParseException exception) {
            sendJson(exchange, 400, Map.of("error", safeMessage(exception)));
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "The tournament database could not complete the request."));
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            sendJson(exchange, 500, Map.of("error", "The server could not complete the request."));
        } finally {
            exchange.close();
        }
    }

    private static void handleApi(HttpExchange exchange, String path, String method, Database database)
            throws IOException, SQLException {
        if (path.equals("/api/auth/login")) {
            if (!method.equals("POST")) {
                sendJson(exchange, 405, Map.of("error", "Use POST to sign in."));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                sendJson(exchange, 413, Map.of("error", "Request is too large."));
                return;
            }
            JsonObject credentials = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (credentials == null || !credentials.has("username") || !credentials.has("password")) {
                sendJson(exchange, 400, Map.of("error", "Enter a username and password."));
                return;
            }
            AuthService.Login login = AUTH.createSession(credentials.get("username").getAsString(), credentials.get("password").getAsString());
            if (login == null) {
                sendJson(exchange, 401, Map.of("error", "That username or password is not valid."));
                return;
            }
            sendJson(exchange, 200, Map.of("username", login.username(), "role", login.role().name(), "token", login.token()));
            return;
        }
        if (path.equals("/api/auth/logout")) {
            if (!method.equals("POST")) {
                sendJson(exchange, 405, Map.of("error", "Use POST to sign out."));
                return;
            }
            AUTH.revokeSession(exchange.getRequestHeaders().getFirst("Authorization"));
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }
        if (path.equals("/api/health")) {
            if (!method.equals("GET")) {
                sendJson(exchange, 405, Map.of("error", "Use GET for the health endpoint."));
                return;
            }
            sendJson(exchange, 200, Map.of("ok", true, "database", database.engine()));
            return;
        }
        if (!path.equals("/api/state")) {
            sendJson(exchange, 404, Map.of("error", "API endpoint not found."));
            return;
        }
        AuthService.Role role = AUTH.roleForAuthorization(exchange.getRequestHeaders().getFirst("Authorization"));
        if (role == null) {
            sendJson(exchange, 401, Map.of("error", "Sign in to view tournament data."));
            return;
        }
        if (method.equals("GET")) {
            sendJson(exchange, 200, database.toJson(database.getState()));
            return;
        }
        if (method.equals("PUT")) {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                sendJson(exchange, 413, Map.of("error", "Request is too large."));
                return;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            Database.LeagueState incoming = database.fromJson(json);
            database.replaceState(incoming, role);
            sendJson(exchange, 200, database.toJson(database.getState()));
            return;
        }
        sendJson(exchange, 405, Map.of("error", "Use GET or PUT for tournament state."));
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = (body instanceof String json ? json : GSON.toJson(body)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "The request is invalid." : message;
    }
}
