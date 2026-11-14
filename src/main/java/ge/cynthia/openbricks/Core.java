package ge.cynthia.openbricks;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Component
public class Core {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private final String name;
    private final String database;
    private final Map<String, Map<String, Object>> tokens = new LinkedHashMap<>();
    private final ThreadLocal<State> state = new ThreadLocal<>();

    public Core(Environment environment) throws SQLException {
        name = environment.getProperty("spring.application.name");
        database = environment.getProperty("DATABASE", name + ".db");
        Object configured = JSON.readValue(environment.getProperty("TOKENS", "{}"), Object.class);
        if (!(configured instanceof Map<?, ?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("Configure TOKENS with local bearer credentials");
        }
        for (var entry : values.entrySet()) {
            String token = text(entry.getKey(), 65536, false);
            Map<String, Object> actor =
                    object(
                            entry.getValue(),
                            Set.of("tenant", "user", "role"),
                            Set.of("tenant", "user", "role"));
            if (token.length() < 24
                    || actor.values().stream()
                            .anyMatch(v -> !(v instanceof String s) || s.isBlank())
                    || !Set.of("admin", "editor", "viewer", "end_user")
                            .contains(actor.get("role"))) {
                throw new IllegalArgumentException("Invalid local bearer principal");
            }
            if (name.equals("openzen")
                    && !String.valueOf(actor.get("user")).matches("[0-9]*[1-9][0-9]*")) {
                throw new IllegalArgumentException(
                        "OpenZen principal user IDs must be positive integers");
            }
            tokens.put(token, actor);
        }
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS records (seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " tenant TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT"
                        + " NOT NULL, UNIQUE(tenant,kind,id))");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS counters (tenant TEXT, kind TEXT, value INTEGER,"
                            + " PRIMARY KEY(tenant,kind))");
        }
    }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", "10000");
        properties.setProperty("transaction_mode", "IMMEDIATE");
        return DriverManager.getConnection("jdbc:sqlite:" + database, properties);
    }

    public ResponseEntity<?> execute(
            HttpServletRequest request,
            byte[] bytes,
            Handler handler,
            Function<Failure, Object> errors)
            throws Exception {
        try {
            if (request.getRequestURI().equals("/healthz")
                    && Set.of("GET", "HEAD").contains(request.getMethod())) {
                return response(new Result(200, map("status", "ok", "service", name)));
            }
            String authorization = Objects.toString(request.getHeader("Authorization"), "");
            String supplied =
                    authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                            ? authorization.substring(7)
                            : "";
            Map<String, Object> actor = null;
            for (var token : tokens.entrySet()) {
                if (MessageDigest.isEqual(
                        token.getKey().getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
                    actor = token.getValue();
                    break;
                }
            }
            if (actor == null) throw new Failure(401, "Valid bearer token required");
            if (request.getRequestURI().equals("/healthz"))
                throw new Failure(405, "Method not allowed");
            int maximum = name.equals("openbricks") ? 10 * 1024 * 1024 + 65536 : 1024 * 1024;
            if (bytes != null && bytes.length > maximum)
                throw new Failure(413, "Request body is too large");
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                HttpServletRequest routed =
                        request.getMethod().equals("HEAD")
                                ? new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                                    public String getMethod() {
                                        return "GET";
                                    }
                                }
                                : request;
                state.set(new State(connection, actor, routed, bytes));
                try {
                    Result result = handler.handle();
                    connection.commit();
                    return response(result);
                } catch (Exception error) {
                    connection.rollback();
                    throw error;
                } finally {
                    state.remove();
                }
            }
        } catch (Failure error) {
            var builder =
                    ResponseEntity.status(error.status)
                            .header("Cache-Control", "no-store")
                            .header("X-Content-Type-Options", "nosniff");
            if (error.status == 401)
                builder.header("WWW-Authenticate", "Bearer realm=\"" + name + "\"");
            return builder.body(errors.apply(error));
        }
    }

    private ResponseEntity<?> response(Result result) {
        var builder =
                ResponseEntity.status(result.status)
                        .header("Cache-Control", "no-store")
                        .header("X-Content-Type-Options", "nosniff");
        result.headers.forEach(builder::header);
        return builder.body(result.body);
    }

    public Map<String, Object> actor() {
        return state.get().actor;
    }

    public HttpServletRequest request() {
        return state.get().request;
    }

    public String argument(String key, String fallback) {
        return Objects.toString(request().getParameter(key), fallback);
    }

    public void parameters(String... allowed) {
        Set<String> names = Set.of(allowed);
        if (request().getParameterMap().entrySet().stream()
                .anyMatch(e -> !names.contains(e.getKey()) || e.getValue().length != 1)) {
            throw new Failure(400, "Unsupported or repeated query parameter");
        }
    }

    public Object body() {
        if (request().getContentType() == null
                || !request()
                        .getContentType()
                        .split(";", 2)[0]
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .matches("application/(json|[^/; ]+\\+json)")) {
            throw new Failure(415, "JSON content type is required");
        }
        try {
            return JSON.readValue(
                    state.get().bytes == null ? new byte[0] : state.get().bytes, Object.class);
        } catch (JacksonException error) {
            throw new Failure(400, "Invalid JSON body");
        }
    }

    public void writable() {
        if (!Set.of("admin", "editor").contains(actor().get("role")))
            throw new Failure(403, "Write permission required");
    }

    public List<Map<String, Object>> rows(String kind) throws SQLException {
        List<Map<String, Object>> values = new ArrayList<>();
        try (PreparedStatement query =
                        prepare(
                                "SELECT data FROM records WHERE tenant=? AND kind=? ORDER BY seq",
                                actor().get("tenant"),
                                kind);
                ResultSet results = query.executeQuery()) {
            while (results.next()) values.add(decode(results.getString(1)));
        }
        return values;
    }

    public Map<String, Object> get(String kind, Object identifier) throws SQLException {
        try (PreparedStatement query =
                        prepare(
                                "SELECT data FROM records WHERE tenant=? AND kind=? AND id=?",
                                actor().get("tenant"),
                                kind,
                                String.valueOf(identifier));
                ResultSet results = query.executeQuery()) {
            if (!results.next()) throw new Failure(404, "Record not found");
            return decode(results.getString(1));
        }
    }

    public Map<String, Object> insert(String kind, Object identifier, Map<String, Object> value)
            throws SQLException {
        try (PreparedStatement query =
                prepare(
                        "INSERT INTO records(tenant,kind,id,data) VALUES(?,?,?,?)",
                        actor().get("tenant"),
                        kind,
                        String.valueOf(identifier),
                        JSON.writeValueAsString(value))) {
            query.executeUpdate();
        }
        return value;
    }

    public Map<String, Object> save(String kind, Object identifier, Map<String, Object> value)
            throws SQLException {
        try (PreparedStatement query =
                prepare(
                        "UPDATE records SET data=? WHERE tenant=? AND kind=? AND id=?",
                        JSON.writeValueAsString(value),
                        actor().get("tenant"),
                        kind,
                        String.valueOf(identifier))) {
            query.executeUpdate();
        }
        return value;
    }

    public void delete(String kind, Object identifier) throws SQLException {
        try (PreparedStatement query =
                prepare(
                        "DELETE FROM records WHERE tenant=? AND kind=? AND id=?",
                        actor().get("tenant"),
                        kind,
                        String.valueOf(identifier))) {
            query.executeUpdate();
        }
    }

    public long sequence(String kind) throws SQLException {
        try (PreparedStatement update =
                prepare(
                        "INSERT INTO counters(tenant,kind,value) VALUES(?,?,1) ON"
                                + " CONFLICT(tenant,kind) DO UPDATE SET value=value+1",
                        actor().get("tenant"),
                        kind)) {
            update.executeUpdate();
        }
        try (PreparedStatement query =
                        prepare(
                                "SELECT value FROM counters WHERE tenant=? AND kind=?",
                                actor().get("tenant"),
                                kind);
                ResultSet results = query.executeQuery()) {
            results.next();
            return results.getLong(1);
        }
    }

    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        PreparedStatement query = state.get().connection.prepareStatement(sql);
        for (int index = 0; index < values.length; index++)
            query.setObject(index + 1, values[index]);
        return query;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String value) {
        return JSON.readValue(value, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(
            Object value, Set<String> allowed, Set<String> required) {
        if (!(value instanceof Map<?, ?> map)
                || !allowed.containsAll(map.keySet())
                || !map.keySet().containsAll(required))
            throw new Failure(400, "Invalid object fields");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    public static Map<String, Object> object(Object value, String... keys) {
        return object(value, Set.of(keys), Set.of(keys));
    }

    public static String text(Object value) {
        return text(value, 65536, false);
    }

    public static String text(Object value, int maximum, boolean blank) {
        if (!(value instanceof String s)
                || s.codePointCount(0, s.length()) > maximum
                || (!blank && s.isBlank())) throw new Failure(400, "Invalid text value");
        return (String) value;
    }

    public static int integer(String value, int minimum, int maximum) {
        if (value == null || !value.matches("[0-9]{1,10}"))
            throw new Failure(400, "Expected an unsigned integer");
        long number = Long.parseLong(value);
        if (number < minimum || number > maximum)
            throw new Failure(400, "Integer outside supported range");
        return (int) number;
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)
            result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    public static String timestamp() {
        return Instant.now().toString();
    }

    public static Result ok(Object value) {
        return new Result(200, value);
    }

    public static Result created(Object value) {
        return new Result(201, value);
    }

    public static Result empty() {
        return new Result(204, null);
    }

    public record Result(int status, Object body, Map<String, String> headers) {
        public Result(int status, Object body) {
            this(status, body, Map.of());
        }
    }

    private record State(
            Connection connection,
            Map<String, Object> actor,
            HttpServletRequest request,
            byte[] bytes) {}

    @FunctionalInterface
    public interface Handler {
        Result handle() throws Exception;
    }

    public static class Failure extends RuntimeException {
        public final int status;
        public final String code;

        public Failure(int status, String message) {
            this(status, message, null);
        }

        public Failure(int status, String message, String code) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public String title() {
            return HttpStatus.valueOf(status).getReasonPhrase();
        }
    }
}
