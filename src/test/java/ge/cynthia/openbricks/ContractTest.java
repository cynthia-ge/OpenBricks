package ge.cynthia.openbricks;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ContractTest {
    static final List<Map<String, Object>> CASES = fixtures();
    static final Path DATABASE = database();
    @Autowired Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE", DATABASE::toString);
        registry.add("TOKENS", () -> Core.JSON.writeValueAsString(CASES.getFirst().get("tokens")));
    }

    @Test
    void explicitBoundaryRegression() throws Exception {
        String token = cast(CASES.getFirst().get("tokens")).keySet().iterator().next();
        try (var client = HttpClient.newHttpClient()) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:"
                                                    + environment.getProperty("local.server.port")
                                                    + "/api/2.0/workspace/unknown"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .method("GET", HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), response.body());
        }
    }

    @TestFactory
    Stream<DynamicTest> contracts() {
        return CASES.stream()
                .map(test ->
                        DynamicTest.dynamicTest((String) test.get("name"), () -> replay(test)));
    }

    void replay(Map<String, Object> test) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM records");
            statement.executeUpdate("DELETE FROM counters");
            statement.executeUpdate("DELETE FROM sqlite_sequence");
        }
        String base = "http://localhost:" + environment.getProperty("local.server.port");
        Map<String, String> substitutions = new LinkedHashMap<>();
        substitutions.put("http://localhost/", base + "/");
        try (HttpClient client = HttpClient.newHttpClient()) {
            int index = 0;
            for (var step : maps(test.get("requests"))) {
                String path = replace((String) step.get("path"), substitutions);
                var request = HttpRequest.newBuilder(URI.create(base + path));
                cast(step.get("headers"))
                        .forEach((key, value) -> request.header(key, (String) value));
                byte[] bytes = Base64.getDecoder().decode((String) step.get("body"));
                String body = replace(new String(bytes, StandardCharsets.UTF_8), substitutions);
                request.method(
                        (String) step.get("method"), HttpRequest.BodyPublishers.ofString(body));
                var response =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                String context =
                        test.get("name")
                                + " #"
                                + (index++)
                                + " "
                                + step.get("method")
                                + " "
                                + path
                                + " => "
                                + new String(response.body(), StandardCharsets.UTF_8);
                assertEquals(
                        ((Number) step.get("status")).intValue(), response.statusCode(), context);
                assertEquals(
                        "no-store",
                        response.headers().firstValue("Cache-Control").orElse(""),
                        context);
                assertEquals(
                        "nosniff",
                        response.headers().firstValue("X-Content-Type-Options").orElse(""),
                        context);
                if (step.get("response") != null) {
                    Object actual = Core.JSON.readValue(response.body(), Object.class);
                    compare(
                            step.get("response"),
                            actual,
                            "",
                            substitutions,
                            response.statusCode() >= 400,
                            context);
                } else if (response.statusCode() != 204 && !step.get("method").equals("HEAD")) {
                    assertArrayEquals(
                            Base64.getDecoder().decode((String) step.get("raw")),
                            response.body(),
                            context);
                }
                if (cast(step.get("response_headers")).containsKey("Content-Disposition")) {
                    assertEquals(
                            cast(step.get("response_headers")).get("Content-Disposition"),
                            response.headers().firstValue("Content-Disposition").orElse(""),
                            context);
                }
            }
        }
    }

    void compare(
            Object expected,
            Object actual,
            String field,
            Map<String, String> bindings,
            boolean error,
            String context) {
        if (error
                && Set.of("errorMessages", "message", "details", "description", "detail")
                        .contains(field)) {
            assertNotNull(actual, context);
            assertFalse(actual.toString().isBlank(), context);
            return;
        }
        if (expected instanceof Map<?, ?> left) {
            assertInstanceOf(Map.class, actual, context);
            var right = cast(actual);
            assertEquals(left.keySet(), right.keySet(), context);
            left.forEach((key, value) ->
                    compare(value, right.get(key), (String) key, bindings, error, context));
        } else if (expected instanceof List<?> left) {
            assertInstanceOf(List.class, actual, context);
            var right = (List<?>) actual;
            assertEquals(left.size(), right.size(), context);
            for (int index = 0; index < left.size(); index++)
                compare(left.get(index), right.get(index), field, bindings, error, context);
        } else if (expected instanceof String value && actual instanceof String result) {
            if (bindings.containsKey(value)) {
                assertEquals(bindings.get(value), result, context);
            } else if (value.matches("\\d{4}-\\d{2}-\\d{2}T.*")
                    && Set.of(
                                    "created",
                                    "updated",
                                    "created_at",
                                    "updated_at",
                                    "createdAt",
                                    "lastEditedAt",
                                    "CreatedDate",
                                    "LastModifiedDate")
                            .contains(field)) {
                assertDoesNotThrow(() -> Instant.parse(result), context);
                bindings.put(value, result);
            } else if (Set.of(
                                    "id",
                                    "Id",
                                    "staticId",
                                    "projectId",
                                    "cellId",
                                    "traceId",
                                    "insertAfterCellId")
                            .contains(field)
                    && (value.matches("[0-9a-f-]{36}") || value.matches("[A-Za-z0-9]{18}"))) {
                assertEquals(value.length(), result.length(), context);
                bindings.put(value, result);
                if (value.length() == 18)
                    bindings.put(value.substring(0, 15), result.substring(0, 15));
            } else {
                assertEquals(replace(value, bindings), result, context);
            }
        } else {
            assertEquals(expected, actual, context);
        }
    }

    static String replace(String value, Map<String, String> bindings) {
        for (String key :
                bindings.keySet().stream()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList()) value = value.replace(key, bindings.get(key));
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    static List<Map<String, Object>> fixtures() {
        try (var input =
                new GZIPInputStream(
                        Objects.requireNonNull(
                                ContractTest.class.getResourceAsStream("/contracts.json.gz")))) {
            return maps(Core.JSON.readValue(input, List.class));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static Path database() {
        try {
            return Files.createTempFile("contract-test-", ".db");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
