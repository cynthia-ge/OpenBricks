package ge.cynthia.openbricks;

import static ge.cynthia.openbricks.Core.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

class CoreTest {
    @TempDir Path directory;

    MockEnvironment environment(String name, Object tokens) {
        return new MockEnvironment()
                .withProperty("spring.application.name", name)
                .withProperty("DATABASE", directory.resolve("core.db").toString())
                .withProperty("TOKENS", JSON.writeValueAsString(tokens));
    }

    Map<String, Object> principal() {
        return map("tenant", "test", "user", "1", "role", "admin");
    }

    MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/test");
        request.addHeader("Authorization", "Bearer " + "a".repeat(32));
        request.setContentType("application/json");
        return request;
    }

    @Test
    void configurationAndPrimitives() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Core(environment("test", Map.of())));
        assertThrows(
                IllegalArgumentException.class, () -> new Core(environment("test", List.of())));
        assertThrows(
                Failure.class, () -> new Core(environment("test", map("a".repeat(32), Map.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Core(environment("test", map("short", principal()))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Core(
                                environment(
                                        "test",
                                        map(
                                                "a".repeat(32),
                                                map("tenant", "t", "user", "1", "role", "root")))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Core(
                                environment(
                                        "openzen",
                                        map(
                                                "a".repeat(32),
                                                map(
                                                        "tenant", "t", "user", "no", "role",
                                                        "admin")))));
        assertEquals("plain", text("plain"));
        assertEquals("", text("", 2, true));
        assertEquals("ok", text("ok", 2, false));
        for (Object value : Arrays.asList(null, List.of(), "", " ", "abc"))
            assertThrows(Failure.class, () -> text(value, 2, false));
        assertEquals(10, integer("10", 1, 10));
        for (String value : Arrays.asList(null, "a", "-1", "²", "０", "0", "11", "9".repeat(4301)))
            assertThrows(Failure.class, () -> integer(value, 1, 10));
        assertEquals(map("x", 1), object(map("x", 1), "x"));
        assertThrows(Failure.class, () -> object(Map.of(), "x"));
        assertThrows(Failure.class, () -> object(List.of(), "x"));
        assertThrows(Failure.class, () -> object(map("x", 1, "extra", 2), "x"));
        assertEquals("Bad Request", new Failure(400, "Bad", "CODE").title());
        assertFalse(timestamp().isBlank());
    }

    @Test
    void transactionalStorageAndValidation() throws Exception {
        var core = new Core(environment("test", map("a".repeat(32), principal())));
        var request = request("POST");
        var failed =
                core.execute(
                        request,
                        "{}".getBytes(StandardCharsets.UTF_8),
                        () -> {
                            assertEquals(1, core.sequence("counter"));
                            core.insert("record", "1", map("value", 1));
                            throw new Failure(400, "rollback");
                        },
                        e -> map("message", e.getMessage()));
        assertEquals(400, failed.getStatusCode().value());
        core.execute(
                request,
                "{}".getBytes(StandardCharsets.UTF_8),
                () -> {
                    assertEquals(List.of(), core.rows("record"));
                    assertThrows(Failure.class, () -> core.get("record", "1"));
                    assertEquals(1, core.sequence("counter"));
                    assertEquals(2, core.sequence("counter"));
                    core.insert("record", "1", map("value", 1));
                    core.save("record", "1", map("value", 2));
                    assertEquals(map("value", 2), core.get("record", "1"));
                    assertEquals(1, core.rows("record").size());
                    core.delete("record", "1");
                    assertEquals(List.of(), core.rows("record"));
                    core.writable();
                    assertEquals(Map.of(), core.body());
                    assertEquals("fallback", core.argument("missing", "fallback"));
                    core.parameters();
                    return new Result(200, map("ok", true), Map.of("X-Test", "yes"));
                },
                e -> Map.of());
        var head = request("HEAD");
        assertEquals(
                200,
                core.execute(
                                head,
                                null,
                                () -> {
                                    assertEquals("GET", core.request().getMethod());
                                    return ok(Map.of());
                                },
                                e -> Map.of())
                        .getStatusCode()
                        .value());
        head.setRequestURI("/healthz");
        head.removeHeader("Authorization");
        assertEquals(
                200,
                core.execute(head, null, () -> empty(), e -> Map.of()).getStatusCode().value());
        var typed = request("POST");
        typed.setContentType("application/problem+json; charset=utf-8");
        assertEquals(
                200,
                core.execute(typed, "{}".getBytes(), () -> ok(core.body()), e -> Map.of())
                        .getStatusCode()
                        .value());
        typed.setContentType("application/json-invalid");
        assertEquals(
                415,
                core.execute(typed, "{}".getBytes(), () -> ok(core.body()), e -> Map.of())
                        .getStatusCode()
                        .value());
        var bad = request("POST");
        bad.setContentType("text/plain");
        assertEquals(
                415,
                core.execute(bad, new byte[0], () -> ok(core.body()), e -> Map.of())
                        .getStatusCode()
                        .value());
        assertEquals(
                400,
                core.execute(request("POST"), "{".getBytes(), () -> ok(core.body()), e -> Map.of())
                        .getStatusCode()
                        .value());
        assertEquals(
                400,
                core.execute(request("POST"), null, () -> ok(core.body()), e -> Map.of())
                        .getStatusCode()
                        .value());
        var repeated = request("GET");
        repeated.addParameter("p", "1", "2");
        assertEquals(
                400,
                core.execute(
                                repeated,
                                null,
                                () -> {
                                    core.parameters("p");
                                    return ok(Map.of());
                                },
                                e -> Map.of())
                        .getStatusCode()
                        .value());
        assertEquals(
                413,
                core.execute(
                                request("POST"),
                                new byte[1024 * 1024 + 1],
                                () -> ok(Map.of()),
                                e -> Map.of())
                        .getStatusCode()
                        .value());
        var reader =
                new Core(
                        environment(
                                "test",
                                map(
                                        "a".repeat(32),
                                        map("tenant", "t", "user", "1", "role", "viewer"))));
        assertEquals(
                403,
                reader.execute(
                                request("POST"),
                                null,
                                () -> {
                                    reader.writable();
                                    return empty();
                                },
                                e -> Map.of())
                        .getStatusCode()
                        .value());
        assertEquals(201, created(Map.of()).status());
        assertEquals(204, empty().status());
    }
}
