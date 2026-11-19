package demo;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 메모리 대역: 레디스(TTL은 기록만) · users 테이블 하나짜리 DB */
final class Fakes {
    private Fakes() {}

    static final class FakeKv implements Kv {
        final Map<String, String> data = new ConcurrentHashMap<>();
        final Map<String, Integer> ttl = new ConcurrentHashMap<>();
        volatile boolean down;

        private void check() {
            if (down) throw new IllegalStateException("redis down");
        }

        @Override public String get(String key) { check(); return data.get(key); }

        @Override public void setex(String key, int seconds, String value) {
            check();
            data.put(key, value);
            ttl.put(key, seconds);
        }

        @Override public synchronized long incr(String key) {
            check();
            long v = Long.parseLong(data.getOrDefault(key, "0")) + 1;
            data.put(key, Long.toString(v));
            return v;
        }

        @Override public long del(String key) { check(); return data.remove(key) == null ? 0 : 1; }

        @Override public long dbsize() { check(); return data.size(); }

        @Override public Object command(String name, String... args) { throw new UnsupportedOperationException(); }
    }

    static final class FakeDb implements Db {
        private static final Pattern BY_ID = Pattern.compile("SELECT \\* FROM users WHERE id = (\\d+)");
        private static final Pattern BY_NAME = Pattern.compile("SELECT \\* FROM users WHERE name = '(.*)'");
        private static final Pattern UPD = Pattern.compile("UPDATE users SET age = (\\d+) WHERE id = (\\d+)");

        record User(String name, long age) {}

        final Map<Long, User> rows = new TreeMap<>();
        volatile boolean down;
        volatile String forcedBody;   // 다음 SELECT 응답을 이걸로 (스트리밍 실패 흉내)
        int selects;

        @Override public synchronized DbResult query(String sql) throws Unavailable {
            if (down) throw new Unavailable("db down");
            var m = UPD.matcher(sql);
            if (m.matches()) {
                long id = Long.parseLong(m.group(2));
                User u = rows.get(id);
                if (u == null) return new DbResult(200, "{\"ok\":true,\"affectedRows\":[0]}");
                rows.put(id, new User(u.name(), Long.parseLong(m.group(1))));
                return new DbResult(200, "{\"ok\":true,\"affectedRows\":[1]}");
            }
            selects++;
            if (forcedBody != null) return new DbResult(200, forcedBody);
            var sb = new StringBuilder("{\"columns\":[\"id\",\"name\",\"age\"]}\n");
            int n = 0;
            var id = BY_ID.matcher(sql);
            var name = BY_NAME.matcher(sql);
            for (var e : rows.entrySet()) {
                boolean match = id.matches() ? e.getKey() == Long.parseLong(id.group(1))
                        : name.matches() && e.getValue().name().equals(name.group(1).replace("''", "'"));
                if (match) {
                    sb.append('[').append(e.getKey()).append(",\"").append(e.getValue().name()).append("\",")
                            .append(e.getValue().age()).append("]\n");
                    n++;
                }
            }
            return new DbResult(200, sb.append("{\"ok\":true,\"rowCount\":").append(n).append("}\n").toString());
        }
    }
}
