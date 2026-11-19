package demo;

import java.nio.file.Path;

/** 환경 변수 설정 (Q23). 기본값은 compose 밖에서 혼자 띄울 때 기준. */
public record Config(
        String redisHost, int redisPort, int redisTimeoutMs,
        String dbUrl, int dbMaxConcurrency,
        int ttlSec, int emptyTtlSec, int maxBytes,
        boolean console, boolean testHooks,
        int port, Path webDir, Path vendorDir) {

    static Config fromEnv() {
        return new Config(
                env("REDIS_HOST", "127.0.0.1"), num("REDIS_PORT", 6379), num("REDIS_TIMEOUT_MS", 50),
                env("DB_URL", "http://127.0.0.1:8080"), num("DB_MAX_CONCURRENCY", 6),
                num("CACHE_TTL_SEC", 60), num("CACHE_EMPTY_TTL_SEC", 5), num("CACHE_MAX_BYTES", 256 * 1024),
                on("DEMO_CONSOLE", true), on("DEMO_TEST_HOOKS", false),
                num("PORT", 8080), Path.of(env("WEB_DIR", "web")), Path.of(env("VENDOR_DIR", "../vendor")));
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int num(String k, int def) {
        return Integer.parseInt(env(k, Integer.toString(def)));
    }

    private static boolean on(String k, boolean def) {
        String v = env(k, def ? "on" : "off").toLowerCase();
        return v.equals("on") || v.equals("true") || v.equals("1");
    }
}
