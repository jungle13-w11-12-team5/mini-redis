package demo;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/**
 * cache-aside + 세대 번호 무효화 (흐름 지도 경로 E, Q13-C).
 *
 * 읽기:  GET users:ver → v  →  GET users:v{v}:{kind}:{arg}  →  hit면 끝
 *        miss → DBMS SELECT → 마지막 줄 ok:true면 SET EX (빈 결과는 짧게, 256KB 넘으면 skip)
 * 쓰기(API 경유):  DBMS UPDATE 성공 → INCR users:ver   (순서 고정: 반대면 INCR 직후 읽은 옛 값이 새 버전 키에 들어감)
 * 쓰기(DB 직접):   UPDATE만. 캐시는 모름 → TTL이 마지막 안전망
 *
 * 비교용 DEL 모드(Q13-B): 키에 버전 없음, API 쓰기 뒤 DEL users:id:{id}. 검색 키는 못 지움 · 경합 창이 남음.
 * 레디스 실패 · 타임아웃 → DB로 바로 (X-Cache: bypass). 캐시는 가속일 뿐 (Q21).
 */
final class UserCache {
    static final String VER_KEY = "users:ver";

    enum Mode { GEN, DEL }

    /** cache = hit | miss | bypass | skip | off */
    record Result(int status, String cache, String body) {}

    /** 테스트 · 경합 재현용: DB 응답을 받은 뒤, SET 하기 전에 끼워 넣는 지점 (C2) */
    interface Hook {
        Hook NONE = () -> {};

        void afterDbRead();
    }

    final class Stats {
        final LongAdder hit = new LongAdder(), miss = new LongAdder(), bypass = new LongAdder(),
                skip = new LongAdder(), off = new LongAdder();

        void count(String cache) {
            switch (cache) {
                case "hit" -> hit.increment();
                case "miss" -> miss.increment();
                case "bypass" -> bypass.increment();
                case "skip" -> skip.increment();
                default -> off.increment();
            }
        }

        void reset() {
            hit.reset(); miss.reset(); bypass.reset(); skip.reset(); off.reset();
        }
    }

    private final Kv kv;
    private final Db db;
    private final int ttlSec, emptyTtlSec, maxBytes;
    final Stats stats = new Stats();
    volatile boolean cacheOn = true;
    volatile Mode mode = Mode.GEN;

    UserCache(Kv kv, Db db, int ttlSec, int emptyTtlSec, int maxBytes) {
        this.kv = kv;
        this.db = db;
        this.ttlSec = ttlSec;
        this.emptyTtlSec = emptyTtlSec;
        this.maxBytes = maxBytes;
    }

    /** 시작 때 1번 (Q13 따라오는 규칙): 레디스가 재시작 · 복구로 ver를 잃거나 되돌려도 옛 키가 다시 살아나지 않게 */
    String bumpOnStart() {
        try {
            return "users:ver → " + kv.incr(VER_KEY);
        } catch (RuntimeException e) {
            return "레디스 연결 안 됨 (" + e.getClass().getSimpleName() + ") — 읽기는 우회로 동작";
        }
    }

    // ---- 읽기

    Result byId(long id, Trace t, Hook hook) {
        return read("id", Long.toString(id), "SELECT * FROM users WHERE id = " + id, true, t, hook);
    }

    Result byName(String name, Trace t, Hook hook) {
        return read("name", name, "SELECT * FROM users WHERE name = " + Sql.quote(name), false, t, hook);
    }

    private Result read(String kind, String arg, String sql, boolean notFound404, Trace t, Hook hook) {
        if (!cacheOn) {
            t.note("demo", "캐시 끔", "DB로 바로");
            return fromDb(sql, null, "off", notFound404, t, hook);
        }
        String key;
        try {
            key = key(kind, arg, t);
        } catch (RuntimeException e) {
            return fromDb(sql, null, "bypass", notFound404, t, hook);
        }
        String cached;
        long s = System.nanoTime();
        try {
            cached = kv.get(key);
            t.add("redis", "GET " + key, cached == null ? "(nil) → miss" : "hit " + cached.length() + "B", s);
        } catch (RuntimeException e) {
            t.add("redis", "GET " + key, "실패 " + fail(e) + " → DB로 우회", s);
            return fromDb(sql, null, "bypass", notFound404, t, hook);
        }
        if (cached != null) {
            stats.count("hit");
            return new Result(statusFor(new DbResult(200, cached), notFound404), "hit", cached);
        }
        return fromDb(sql, key, "miss", notFound404, t, hook);
    }

    /** GEN: users:v{ver}:kind:arg (ver 없으면 0) · DEL: users:kind:arg. 레디스 실패 · ver 손상이면 던진다 */
    private String key(String kind, String arg, Trace t) {
        if (mode == Mode.DEL) return "users:" + kind + ":" + arg;
        long s = System.nanoTime();
        String v;
        try {
            v = kv.get(VER_KEY);
        } catch (RuntimeException e) {
            t.add("redis", "GET " + VER_KEY, "실패 " + fail(e) + " → DB로 우회", s);
            throw e;
        }
        long ver;
        try {
            ver = v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            t.add("redis", "GET " + VER_KEY, "정수가 아님 '" + v + "' → DB로 우회", s);
            throw new IllegalStateException("ver 손상");
        }
        t.add("redis", "GET " + VER_KEY, v == null ? "(nil) → 0" : v, s);
        return "users:v" + ver + ":" + kind + ":" + arg;
    }

    /** key == null이면 캐시에 쓰지 않는다 (off · bypass) */
    private Result fromDb(String sql, String key, String cache, boolean notFound404, Trace t, Hook hook) {
        DbResult r;
        long s = System.nanoTime();
        try {
            r = db.query(sql);
        } catch (Db.Unavailable e) {
            t.add("minidb", "POST /query " + sql, "실패 " + e.getMessage(), s);
            stats.count(cache);
            return new Result(503, cache, error("DB 사용 불가: " + e.getMessage()));
        }
        if (!r.selectOk()) {
            // DB 실패 → 캐시에 안 씀, 에러 그대로 (Q21). 200인데 마지막 줄 ok:false = 스트리밍 도중 실패 (Q16)
            t.add("minidb", "POST /query " + sql, "HTTP " + r.status() + " " + r.lastLine() + " → 캐시 안 함", s);
            stats.count(cache);
            return new Result(r.status() == 200 ? 502 : r.status(), cache, r.body());
        }
        long rows = r.rowCount();
        t.add("minidb", "POST /query " + sql, rows + "행 · " + r.body().length() + "자", s);
        hook.afterDbRead();
        if (key != null) {
            int bytes = r.body().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                cache = "skip";
                t.note("demo", "SET 생략", bytes + "B > 상한 " + maxBytes + "B (X-Cache: skip)");
            } else {
                int ttl = rows == 0 ? emptyTtlSec : ttlSec;
                long s2 = System.nanoTime();
                try {
                    kv.setex(key, ttl, r.body());
                    t.add("redis", "SET " + key + " <" + bytes + "B> EX " + ttl, "OK", s2);
                } catch (RuntimeException e) {
                    t.add("redis", "SET " + key + " EX " + ttl, "실패 " + fail(e) + " (응답은 그대로)", s2);
                }
            }
        }
        stats.count(cache);
        return new Result(statusFor(r, notFound404), cache, r.body());
    }

    private static int statusFor(DbResult r, boolean notFound404) {
        return notFound404 && r.rowCount() == 0 ? 404 : 200;
    }

    // ---- 쓰기

    /** viaApi = true: DB UPDATE 성공 → 무효화(GEN: INCR, DEL: DEL id 키). false: DB만 (레디스는 모름) */
    Result update(long id, String name, Long age, boolean viaApi, Trace t) {
        var set = new StringBuilder();
        if (name != null) set.append("name = ").append(Sql.quote(name));
        if (age != null) set.append(set.isEmpty() ? "" : ", ").append("age = ").append(age);
        if (set.isEmpty()) return new Result(400, "-", error("name이나 age 중 하나는 필요"));
        String sql = "UPDATE users SET " + set + " WHERE id = " + id;

        long s = System.nanoTime();
        DbResult r;
        try {
            r = db.query(sql);
        } catch (Db.Unavailable e) {
            t.add("minidb", "POST /query " + sql, "실패 " + e.getMessage(), s);
            return new Result(503, "-", error("DB 사용 불가: " + e.getMessage()));
        }
        if (!r.modifyOk()) {
            t.add("minidb", "POST /query " + sql, "HTTP " + r.status() + " → 무효화 안 함", s);
            return new Result(r.status() == 200 ? 502 : r.status(), "-", r.body());
        }
        long affected = r.firstAffected();
        t.add("minidb", "POST /query " + sql, "affectedRows " + affected, s);

        String inval;
        if (!viaApi) {
            inval = "없음 (DB 직접 — 캐시는 TTL까지 옛 값)";
            t.note("demo", "무효화", inval);
        } else if (affected <= 0) {
            inval = "없음 (바뀐 행 0)";
            t.note("demo", "무효화", inval);
        } else {
            inval = invalidate(id, t);
        }
        String body = "{\"ok\":true,\"affected\":" + affected + ",\"via\":\"" + (viaApi ? "api" : "direct")
                + "\",\"mode\":\"" + mode.name().toLowerCase() + "\",\"invalidation\":" + Json.str(inval) + "}";
        return new Result(affected == 0 ? 404 : 200, "-", body);
    }

    private String invalidate(long id, Trace t) {
        long s = System.nanoTime();
        if (mode == Mode.GEN) {
            try {
                long v = kv.incr(VER_KEY);
                t.add("redis", "INCR " + VER_KEY, Long.toString(v), s);
                return "INCR " + VER_KEY + " → " + v + " (users 캐시 전체가 옛 버전이 됨)";
            } catch (RuntimeException e) {
                t.add("redis", "INCR " + VER_KEY, "실패 " + fail(e), s);
                return "실패: INCR " + fail(e) + " → TTL까지 옛 값";
            }
        }
        String key = "users:id:" + id;
        try {
            long n = kv.del(key);
            t.add("redis", "DEL " + key, Long.toString(n), s);
            return "DEL " + key + " → " + n + " (검색 결과 키는 못 지움 → TTL까지)";
        } catch (RuntimeException e) {
            t.add("redis", "DEL " + key, "실패 " + fail(e), s);
            return "실패: DEL " + fail(e) + " → TTL까지 옛 값";
        }
    }

    static String error(String message) {
        return "{\"ok\":false,\"error\":" + Json.str(message) + "}";
    }

    private static String fail(RuntimeException e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 60 ? m.substring(0, 60) : m));
    }
}
