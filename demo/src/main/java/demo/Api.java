package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 입구. 라우팅 · 입력 검사 · 헤더만 하고 캐시 판단은 UserCache가 한다.
 *
 * GET  /api/users/{id}            id 조회 (캐시 경유)
 * GET  /api/users?name=..         이름 검색 = DB 전체 스캔 (캐시 경유)
 * PUT  /api/users/{id}?age=&name= 쓰기 — API 경유 (DB 성공 → 무효화)
 * PUT  /api/db/users/{id}?age=..  쓰기 — DB 직접 (캐시는 모름)
 * GET|POST /api/settings?cache=on|off&mode=gen|del
 * GET  /api/stats · POST /api/stats/reset
 * POST /api/console/redis · /api/console/db   직접 호출 창 (DEMO_CONSOLE=on일 때만)
 * GET  /api/health
 * 그 밖의 GET → web/ 정적 파일, /vendor/mini-react/* → vendor 복사본
 *
 * 요청 헤더 X-Trace: 1 → 응답 헤더 X-Trace에 단계 JSON. X-Test-Pause-Ms → DB 읽은 뒤 SET 전 대기 (DEMO_TEST_HOOKS=on일 때만)
 */
final class Api {
    private static final Pattern USER = Pattern.compile("^/api/users/([^/]+)$");
    private static final Pattern DB_USER = Pattern.compile("^/api/db/users/([^/]+)$");
    private static final String NDJSON = "application/x-ndjson; charset=utf-8";
    private static final String JSON = "application/json; charset=utf-8";

    private final UserCache cache;
    private final Kv kv;
    private final Console console;
    private final Config cfg;

    Api(UserCache cache, Kv kv, Console console, Config cfg) {
        this.cache = cache;
        this.kv = kv;
        this.console = console;
        this.cfg = cfg;
    }

    void mount(HttpServer server) {
        server.createContext("/api/", this::api);
        server.createContext("/", this::files);
    }

    // ---- /api

    private void api(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Trace t = "1".equals(ex.getRequestHeaders().getFirst("X-Trace")) ? Trace.on() : Trace.off();
            try {
                route(ex, method, path, t);
            } catch (Sql.BadInput e) {
                send(ex, 400, JSON, UserCache.error(e.getMessage()), null, t);
            } catch (RuntimeException e) {
                send(ex, 500, JSON, UserCache.error("서버 내부 오류: " + e), null, t);
            }
        }
    }

    private void route(HttpExchange ex, String method, String path, Trace t) throws IOException, Sql.BadInput {
        Map<String, byte[]> q = Query.parse(ex.getRequestURI().getRawQuery());
        Matcher m;
        if (path.equals("/api/health")) {
            send(ex, 200, JSON, "{\"ok\":true}", null, t);
        } else if (path.equals("/api/users") && method.equals("GET")) {
            String name = Sql.name(q.get("name"));
            reply(ex, cache.byName(name, t, hook(ex, t)), t);
        } else if ((m = USER.matcher(path)).matches() && method.equals("GET")) {
            reply(ex, cache.byId(Sql.id(m.group(1)), t, hook(ex, t)), t);
        } else if ((m = USER.matcher(path)).matches() && method.equals("PUT")) {
            reply(ex, write(Sql.id(m.group(1)), q, true, t), t);
        } else if ((m = DB_USER.matcher(path)).matches() && method.equals("PUT")) {
            reply(ex, write(Sql.id(m.group(1)), q, false, t), t);
        } else if (path.equals("/api/settings")) {
            if (method.equals("POST")) settings(q);
            send(ex, 200, JSON, settingsJson(), null, t);
        } else if (path.equals("/api/stats") && method.equals("GET")) {
            send(ex, 200, JSON, statsJson(), null, t);
        } else if (path.equals("/api/stats/reset") && method.equals("POST")) {
            cache.stats.reset();
            send(ex, 200, JSON, statsJson(), null, t);
        } else if (path.startsWith("/api/console/") && cfg.console() && method.equals("POST")) {
            if (!local(ex.getRemoteAddress().getAddress())) {
                send(ex, 403, JSON, UserCache.error("직접 호출 창은 로컬에서만"), null, t);
                return;
            }
            String body = readBody(ex.getRequestBody());
            switch (path) {
                case "/api/console/redis" -> send(ex, 200, JSON, console.redis(body), null, t);
                case "/api/console/db" -> send(ex, 200, JSON, console.db(body), null, t);
                default -> send(ex, 404, JSON, UserCache.error("없는 경로"), null, t);
            }
        } else {
            send(ex, 404, JSON, UserCache.error("없는 경로: " + method + " " + path), null, t);
        }
    }

    private UserCache.Result write(long id, Map<String, byte[]> q, boolean viaApi, Trace t) throws Sql.BadInput {
        String name = q.containsKey("name") ? Sql.name(q.get("name")) : null;
        Long age = q.containsKey("age") ? Sql.age(q.get("age")) : null;
        return cache.update(id, name, age, viaApi, t);
    }

    private UserCache.Hook hook(HttpExchange ex, Trace t) {
        if (!cfg.testHooks()) return UserCache.Hook.NONE;
        String v = ex.getRequestHeaders().getFirst("X-Test-Pause-Ms");
        if (v == null) return UserCache.Hook.NONE;
        long ms = Math.min(Math.max(Long.parseLong(v.trim()), 0), 10_000);
        return () -> {
            long s = System.nanoTime();
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            t.add("demo", "테스트 대기 (DB 읽은 뒤 · SET 전)", ms + "ms", s);
        };
    }

    private void settings(Map<String, byte[]> q) throws Sql.BadInput {
        if (q.containsKey("cache")) {
            String v = new String(q.get("cache"), StandardCharsets.UTF_8);
            if (!v.equals("on") && !v.equals("off")) throw new Sql.BadInput("cache는 on|off");
            cache.cacheOn = v.equals("on");
        }
        if (q.containsKey("mode")) {
            String v = new String(q.get("mode"), StandardCharsets.UTF_8);
            if (!v.equals("gen") && !v.equals("del")) throw new Sql.BadInput("mode는 gen|del");
            cache.mode = v.equals("gen") ? UserCache.Mode.GEN : UserCache.Mode.DEL;
        }
    }

    private String settingsJson() {
        return "{\"cache\":" + Json.str(cache.cacheOn ? "on" : "off") + ",\"mode\":" + Json.str(cache.mode.name().toLowerCase())
                + ",\"console\":" + cfg.console() + ",\"testHooks\":" + cfg.testHooks()
                + ",\"ttlSec\":" + cfg.ttlSec() + ",\"emptyTtlSec\":" + cfg.emptyTtlSec() + ",\"maxBytes\":" + cfg.maxBytes()
                + ",\"dbMaxConcurrency\":" + cfg.dbMaxConcurrency() + "}";
    }

    private String statsJson() {
        var s = cache.stats;
        String dbsize, ver;
        try {
            dbsize = Long.toString(kv.dbsize());
            String v = kv.get(UserCache.VER_KEY);
            ver = v == null ? "null" : Json.str(v);
        } catch (RuntimeException e) {
            dbsize = "null";
            ver = "null";
        }
        return "{\"hit\":" + s.hit.sum() + ",\"miss\":" + s.miss.sum() + ",\"bypass\":" + s.bypass.sum()
                + ",\"skip\":" + s.skip.sum() + ",\"off\":" + s.off.sum() + ",\"dbsize\":" + dbsize + ",\"ver\":" + ver + "}";
    }

    private static boolean local(InetAddress a) {
        // compose는 포트를 호스트 127.0.0.1에만 연다. 컨테이너 안에서 보이는 주소는 도커 브리지(사설)
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    private void reply(HttpExchange ex, UserCache.Result r, Trace t) throws IOException {
        String type = r.body().startsWith("{\"ok\"") || r.body().startsWith("{\"ok\":false") ? JSON : NDJSON;
        send(ex, r.status(), type, r.body(), r.cache(), t);
    }

    private static void send(HttpExchange ex, int status, String type, String body, String cacheHeader, Trace t) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        if (cacheHeader != null && !cacheHeader.equals("-")) h.set("X-Cache", cacheHeader);
        if (t.enabled()) h.set("X-Trace", t.toJson());
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(64 * 1024 + 1);
        if (b.length > 64 * 1024) throw new IOException("본문이 64KB를 넘음");
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---- 정적 파일

    private void files(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, JSON, UserCache.error("GET만"), null, Trace.off());
                return;
            }
            Path root = cfg.webDir();
            String rel = path.equals("/") ? "index.html" : path.substring(1);
            if (rel.startsWith("vendor/")) {
                root = cfg.vendorDir();
                rel = rel.substring("vendor/".length());
            }
            Path base = root.toAbsolutePath().normalize();
            Path file = base.resolve(rel).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                send(ex, 404, JSON, UserCache.error("없는 파일"), null, Trace.off());
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return JSON;
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
