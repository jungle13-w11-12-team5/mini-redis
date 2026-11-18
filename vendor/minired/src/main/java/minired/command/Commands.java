package minired.command;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import minired.resp.Reply;
import minired.resp.RespParser;
import minired.store.Keyspace;

/**
 * 처리 함수 (Q6). 인자 개수는 CommandTable이 이미 검사했다.
 * 저장소는 Keyspace의 공개 함수로만 만진다 → 모두 키 찾기 함수를 거친다 (ADR-0001).
 */
public final class Commands {
    static final String NOT_INTEGER = "ERR value is not an integer or out of range";
    static final String SYNTAX = "ERR syntax error";

    private final Keyspace ks;

    public Commands(Keyspace ks) {
        this.ks = ks;
    }

    Reply ping(byte[][] a) {
        if (a.length > 2) return Reply.error("ERR wrong number of arguments for 'ping' command");
        return a.length == 2 ? Reply.bulk(a[1]) : Reply.PONG;
    }

    /** SET key value [NX|XX] [EX s|PX ms] */
    Reply set(byte[][] a) {
        boolean nx = false;
        boolean xx = false;
        String ttlUnit = null;
        long ttlMillis = 0;
        for (int i = 3; i < a.length; i++) {
            String opt = new String(a[i], StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "nx" -> { if (xx) return Reply.error(SYNTAX); nx = true; }
                case "xx" -> { if (nx) return Reply.error(SYNTAX); xx = true; }
                case "ex", "px" -> {
                    if ((ttlUnit != null && !ttlUnit.equals(opt)) || i + 1 >= a.length) return Reply.error(SYNTAX);
                    ttlUnit = opt;
                    Long ms = toMillis(a[++i], opt.equals("ex"));
                    if (ms == null) return Reply.error(NOT_INTEGER);
                    if (ms <= 0 || ms > Keyspace.MAX_TTL_MILLIS) {
                        return Reply.error("ERR invalid expire time in 'set' command");
                    }
                    ttlMillis = ms;
                }
                default -> { return Reply.error(SYNTAX); }
            }
        }
        if (nx || xx) {
            boolean exists = ks.exists(a[1]);
            if ((nx && exists) || (xx && !exists)) return Reply.NIL;
        }
        if (ttlUnit != null) {
            ks.setWithTtl(a[1], a[2], ttlMillis);
        } else {
            ks.set(a[1], a[2]);
        }
        return Reply.OK;
    }

    Reply get(byte[][] a) {
        return Reply.bulk(ks.get(a[1]));
    }

    Reply del(byte[][] a) {
        long n = 0;
        for (int i = 1; i < a.length; i++) {
            if (ks.delete(a[i])) n++;
        }
        return Reply.integer(n);
    }

    /** 같은 키를 여러 번 주면 여러 번 센다 (Redis와 같음). */
    Reply exists(byte[][] a) {
        long n = 0;
        for (int i = 1; i < a.length; i++) {
            if (ks.exists(a[i])) n++;
        }
        return Reply.integer(n);
    }

    /** EXPIRE key seconds. 0 이하면 키를 지운다 (B12). */
    Reply expire(byte[][] a) {
        Long ms = toMillis(a[2], true);
        if (ms == null) return Reply.error(NOT_INTEGER);
        if (ms > Keyspace.MAX_TTL_MILLIS) return Reply.error("ERR invalid expire time in 'expire' command");
        return Reply.integer(ks.expire(a[1], ms) ? 1 : 0);
    }

    /** 남은 초. 반올림은 Redis와 같다. 없음 -2, 기간 없음 -1. */
    Reply ttl(byte[][] a) {
        long ms = ks.ttlMillis(a[1]);
        return Reply.integer(ms < 0 ? ms : (ms + 500) / 1000);
    }

    /** DBSIZE. Phase 2 bench가 옛 세대 키가 쌓이는 양을 본다 (Q2 지도 Q22) */
    Reply dbsize(byte[][] a) {
        return Reply.integer(ks.dbsize());
    }

    /** INCR key. 없는 키는 0에서 시작, 기간은 유지 (B7). */
    Reply incr(byte[][] a) {
        byte[] old = ks.get(a[1]);
        long n = 0;
        if (old != null) {
            try {
                n = RespParser.parseLong(old);
            } catch (NumberFormatException e) {
                return Reply.error(NOT_INTEGER);
            }
        }
        if (n == Long.MAX_VALUE) return Reply.error("ERR increment or decrement would overflow");
        byte[] next = Long.toString(n + 1).getBytes(StandardCharsets.US_ASCII);
        if (old == null) {
            ks.set(a[1], next);
        } else {
            ks.replaceValue(a[1], next);
        }
        return Reply.integer(n + 1);
    }

    /** @return ms (×1000에서 넘치면 long 끝값으로 고정 → 범위 검사가 잡는다), 정수가 아니면 null */
    private static Long toMillis(byte[] arg, boolean seconds) {
        long v;
        try {
            v = RespParser.parseLong(arg);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!seconds) return v;
        try {
            return Math.multiplyExact(v, 1000L);
        } catch (ArithmeticException e) {
            return v > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
