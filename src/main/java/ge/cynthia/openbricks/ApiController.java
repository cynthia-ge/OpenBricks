package ge.cynthia.openbricks;

import static ge.cynthia.openbricks.Core.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

@RestController
public class ApiController {
    private final Core core;
    private static final String BASE = "/api/2.0/workspace/";
    private static final int LIMIT = 10 * 1024 * 1024;

    public ApiController(Core core) {
        this.core = core;
    }

    @RequestMapping("/**")
    public ResponseEntity<?> dispatch(
            HttpServletRequest request, @RequestBody(required = false) byte[] bytes)
            throws Exception {
        return core.execute(
                request,
                bytes,
                this::route,
                e ->
                        map(
                                "error_code",
                                e.code != null
                                        ? e.code
                                        : switch (e.status) {
                                            case 400 -> "INVALID_PARAMETER_VALUE";
                                            case 401 -> "UNAUTHENTICATED";
                                            case 403 -> "PERMISSION_DENIED";
                                            case 404 -> "RESOURCE_DOES_NOT_EXIST";
                                            case 413 -> "MAX_NOTEBOOK_SIZE_EXCEEDED";
                                            default -> "REQUEST_ERROR";
                                        },
                                "message",
                                e.getMessage()));
    }

    private Result route() throws Exception {
        String path = core.request().getRequestURI().replaceAll("/+$", "");
        String method = core.request().getMethod();
        if (!path.startsWith(BASE)) throw new Failure(404, "Resource not found");
        String action = path.substring(BASE.length());
        if (Set.of("get-status", "list", "export").contains(action)) {
            if (!method.equals("GET")) throw new Failure(405, "Method not allowed");
            return read(action);
        }
        if (!Set.of("mkdirs", "import", "delete").contains(action))
            throw new Failure(404, "Resource not found");
        if (!method.equals("POST")) throw new Failure(405, "Method not allowed");
        core.parameters();
        core.writable();
        if (action.equals("mkdirs")) {
            String target = pathValue(object(core.body(), "path").get("path"));
            String current = "";
            for (String part :
                    target.equals("/")
                            ? List.<String>of()
                            : List.of(target.substring(1).split("/"))) {
                current += "/" + part;
                var existing = find(current);
                if (existing != null) {
                    if (!existing.get("object_type").equals("DIRECTORY"))
                        throw new Failure(
                                409, "A notebook occupies the path", "RESOURCE_ALREADY_EXISTS");
                } else {
                    core.insert(
                            "object",
                            current,
                            map(
                                    "path",
                                    current,
                                    "object_id",
                                    core.sequence("object"),
                                    "object_type",
                                    "DIRECTORY"));
                }
            }
            return ok(Map.of());
        }
        if (action.equals("import")) return importNotebook();
        var body = object(core.body(), Set.of("path", "recursive"), Set.of("path"));
        String target = pathValue(body.get("path"));
        lookup(target);
        Object recursive = body.getOrDefault("recursive", false);
        if (target.equals("/") || !(recursive instanceof Boolean))
            throw new Failure(400, "Root is protected; recursive must be boolean");
        var children =
                core.rows("object").stream()
                        .filter(item -> ((String) item.get("path")).startsWith(target + "/"))
                        .toList();
        if (!children.isEmpty() && !recursive.equals(true))
            throw new Failure(400, "Use recursive to delete children", "DIRECTORY_NOT_EMPTY");
        for (var item : children) core.delete("object", item.get("path"));
        core.delete("object", target);
        return ok(Map.of());
    }

    private Result read(String action) throws Exception {
        if (action.equals("export")) {
            core.parameters("path", "format", "direct_download");
            String direct = core.argument("direct_download", "false");
            if (!Set.of("true", "false").contains(direct))
                throw new Failure(400, "direct_download must be true or false");
            if (!core.argument("format", "SOURCE").equals("SOURCE"))
                throw new Failure(400, "Only SOURCE export is supported");
            var item = lookup(pathValue(core.argument("path", null)));
            if (!item.get("object_type").equals("NOTEBOOK"))
                throw new Failure(400, "Directory export is not implemented");
            if (direct.equals("true"))
                return new Result(
                        200,
                        Base64.getDecoder().decode((String) item.get("content")),
                        Map.of(
                                "Content-Type",
                                "application/octet-stream",
                                "Content-Disposition",
                                "attachment; filename=\"notebook.txt\""));
            return ok(map("content", item.get("content")));
        }
        core.parameters("path");
        var item = lookup(pathValue(core.argument("path", null)));
        if (action.equals("get-status")) return ok(visible(item));
        if (!item.get("object_type").equals("DIRECTORY"))
            return ok(map("objects", List.of(visible(item))));
        String prefix = ((String) item.get("path")).replaceAll("/+$", "") + "/";
        var objects =
                core.rows("object").stream()
                        .filter(value ->
                                ((String) value.get("path")).startsWith(prefix)
                                        && !((String) value.get("path"))
                                                .substring(prefix.length())
                                                .contains("/"))
                        .map(this::visible)
                        .toList();
        return ok(map("objects", objects));
    }

    private Result importNotebook() throws Exception {
        var body =
                object(
                        core.body(),
                        Set.of("path", "content", "language", "format", "overwrite"),
                        Set.of("path", "content", "language"));
        String target = pathValue(body.get("path"));
        if (!Objects.equals(body.getOrDefault("format", "SOURCE"), "SOURCE")
                || !List.of("PYTHON", "SQL", "SCALA", "R").contains(body.get("language")))
            throw new Failure(
                    400, "Only SOURCE notebooks with PYTHON, SQL, SCALA or R are supported");
        if (!(body.getOrDefault("overwrite", false) instanceof Boolean))
            throw new Failure(400, "overwrite must be boolean");
        String content = text(body.get("content"), LIMIT + 65536, true);
        if (content.length() > LIMIT)
            throw new Failure(400, "Encoded source exceeds 10 MiB", "MAX_NOTEBOOK_SIZE_EXCEEDED");
        try {
            if (content.length() % 4 != 0)
                throw new IllegalArgumentException("Base64 padding required");
            byte[] decoded = Base64.getDecoder().decode(content);
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded));
        } catch (IllegalArgumentException | CharacterCodingException error) {
            throw new Failure(400, "Content must be base64 encoded UTF-8 source");
        }
        String parent = target.substring(0, target.lastIndexOf('/'));
        if (!lookup(parent.isEmpty() ? "/" : parent).get("object_type").equals("DIRECTORY"))
            throw new Failure(409, "Parent is not a directory", "RESOURCE_ALREADY_EXISTS");
        var existing = find(target);
        if (target.equals("/")
                || (existing != null
                        && (!body.getOrDefault("overwrite", false).equals(true)
                                || !existing.get("object_type").equals("NOTEBOOK"))))
            throw new Failure(409, "Workspace object already exists", "RESOURCE_ALREADY_EXISTS");
        var item =
                map(
                        "path",
                        target,
                        "object_type",
                        "NOTEBOOK",
                        "object_id",
                        existing == null ? core.sequence("object") : existing.get("object_id"),
                        "language",
                        body.get("language"),
                        "content",
                        content);
        if (existing == null) core.insert("object", target, item);
        else core.save("object", target, item);
        return ok(Map.of());
    }

    private String pathValue(Object value) {
        String path = text(value, 4096, false);
        if (!path.equals("/")
                && (!path.startsWith("/")
                        || Arrays.stream(path.substring(1).split("/", -1))
                                .anyMatch(p -> Set.of("", ".", "..").contains(p))))
            throw new Failure(400, "Use a normalized absolute workspace path");
        if (path.contains("\\") || path.codePoints().anyMatch(c -> c < 32))
            throw new Failure(400, "Invalid workspace path");
        return path;
    }

    private Map<String, Object> lookup(String path) throws Exception {
        if (path.equals("/")) return map("path", "/", "object_id", 0, "object_type", "DIRECTORY");
        try {
            return core.get("object", path);
        } catch (Failure error) {
            throw new Failure(404, "Workspace object does not exist", "RESOURCE_DOES_NOT_EXIST");
        }
    }

    private Map<String, Object> find(String path) throws Exception {
        return core.rows("object").stream()
                .filter(item -> item.get("path").equals(path))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> visible(Map<String, Object> item) {
        var result = new LinkedHashMap<>(item);
        result.remove("content");
        return result;
    }
}
