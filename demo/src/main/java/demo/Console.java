package demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * 직접 호출 창 (Q26): 캐시 로직을 거치지 않고 레디스 · DB를 직접 부른다.
 * 레디스는 허용 목록만. DEMO_CONSOLE=off면 엔드포인트 자체가 없다 (Api가 등록 안 함).
 */
final class Console {
    static final Set<String> REDIS_ALLOWED = Set.of("GET", "SET", "DEL", "TTL", "EXISTS", "DBSIZE");

    private final Kv kv;
    private final Db db;

    Console(Kv kv, Db db) {
        this.kv = kv;
        this.db = db;
    }

    /** 한 줄 명령 → {"ok":..,"reply":..,"us":..} */
    String redis(String line) {
        List<String> args;
        try {
            args = tokenize(line);
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"reply\":" + Json.str("(error) " + e.getMessage()) + ",\"us\":0}";
        }
        if (args.isEmpty()) return "{\"ok\":false,\"reply\":\"(error) 빈 명령\",\"us\":0}";
        String name = args.get(0).toUpperCase(Locale.ROOT);
        if (!REDIS_ALLOWED.contains(name)) {
            return "{\"ok\":false,\"reply\":" + Json.str("(error) 허용 안 된 명령 — " + String.join(" ", REDIS_ALLOWED.stream().sorted().toList())) + ",\"us\":0}";
        }
        long s = System.nanoTime();
        String reply;
        boolean ok = true;
        try {
            reply = format(kv.command(name, args.subList(1, args.size()).toArray(String[]::new)));
        } catch (JedisDataException e) {
            reply = "(error) " + e.getMessage();
        } catch (RuntimeException e) {
            ok = false;
            reply = "(연결 실패) " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        long us = (System.nanoTime() - s) / 1000;
        return "{\"ok\":" + ok + ",\"reply\":" + Json.str(reply) + ",\"us\":" + us + "}";
    }

    /** SQL 원문 → {"ok":..,"status":..,"body":..,"us":..} */
    String db(String sql) {
        long s = System.nanoTime();
        try {
            DbResult r = db.query(sql);
            long us = (System.nanoTime() - s) / 1000;
            String body = r.body();
            boolean big = body.length() > 64 * 1024;
            if (big) body = body.substring(0, 64 * 1024) + "\n… (64KB에서 자름)";
            return "{\"ok\":true,\"status\":" + r.status() + ",\"body\":" + Json.str(body) + ",\"us\":" + us + "}";
        } catch (Db.Unavailable e) {
            long us = (System.nanoTime() - s) / 1000;
            return "{\"ok\":false,\"status\":0,\"body\":" + Json.str(e.getMessage()) + ",\"us\":" + us + "}";
        }
    }

    static String format(Object o) {
        if (o == null) return "(nil)";
        if (o instanceof byte[] b) return "\"" + new String(b, StandardCharsets.UTF_8) + "\"";
        if (o instanceof Long l) return "(integer) " + l;
        if (o instanceof List<?> list) {
            var b = new StringBuilder();
            for (int i = 0; i < list.size(); i++) b.append(i + 1).append(") ").append(format(list.get(i))).append('\n');
            return b.toString().trim();
        }
        return o.toString();
    }

    /** 공백으로 나눔. "..." 안의 공백은 유지, \" 이스케이프 */
    static List<String> tokenize(String line) {
        var out = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean inQ = false, has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '\\' && i + 1 < line.length()) cur.append(line.charAt(++i));
                else if (c == '"') inQ = false;
                else cur.append(c);
            } else if (c == '"') {
                inQ = true;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (inQ) throw new IllegalArgumentException("닫히지 않은 따옴표");
        if (has) out.add(cur.toString());
        return out;
    }
}
