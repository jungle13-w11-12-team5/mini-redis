package minired.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import minired.resp.Reply;
import minired.store.FakeClock;
import minired.store.Keyspace;
import org.junit.jupiter.api.Test;

class CommandTableTest {
    private final FakeClock clock = new FakeClock();
    private final CommandTable table = new CommandTable(new Commands(new Keyspace(clock)));

    private Reply run(String... words) {
        byte[][] args = new byte[words.length][];
        for (int i = 0; i < words.length; i++) args[i] = words[i].getBytes(StandardCharsets.UTF_8);
        return table.execute(args);
    }

    private static String show(Reply r) {
        return new String(Reply.encode(r), StandardCharsets.UTF_8).trim();
    }

    private void expect(String reply, String... cmd) {
        assertEquals(reply, show(run(cmd)), String.join(" ", cmd));
    }

    @Test
    void 이름은_대소문자_무시_모르는_명령과_인자_수는_에러() {
        expect("+PONG", "PiNg");
        expect("$2\r\nhi", "ping", "hi");
        expect("-ERR wrong number of arguments for 'ping' command", "ping", "a", "b");
        expect("-ERR wrong number of arguments for 'get' command", "get");
        expect("-ERR unknown command 'foo', with args beginning with: 'a'", "foo", "a");
    }

    @Test
    void SET_옵션() {
        expect("+OK", "set", "k", "v");
        expect("$-1", "set", "k", "v2", "NX");
        expect("+OK", "set", "k", "v3", "xx");
        expect("$-1", "set", "none", "v", "XX");
        expect("-ERR syntax error", "set", "k", "v", "nx", "xx");
        expect("-ERR syntax error", "set", "k", "v", "ex", "1", "px", "1000");
        expect("-ERR syntax error", "set", "k", "v", "ex");
        expect("-ERR syntax error", "set", "k", "v", "keepttl");
        expect("-ERR value is not an integer or out of range", "set", "k", "v", "ex", "abc");
        expect("-ERR invalid expire time in 'set' command", "set", "k", "v", "ex", "0");
        expect("-ERR invalid expire time in 'set' command", "set", "k", "v", "ex", "9223372036854775807");
    }

    @Test
    void SET_NX_EX_함께() {
        expect("+OK", "set", "lock", "a", "nx", "ex", "10");
        expect("$-1", "set", "lock", "b", "nx", "ex", "10"); // 이미 있음 → 기간도 안 바뀜
        clock.advanceMillis(4000);
        expect(":6", "ttl", "lock");
        clock.advanceMillis(6000);
        expect("+OK", "set", "lock", "b", "nx", "ex", "10"); // 만료 뒤에는 없는 키
    }

    @Test
    void DEL_EXISTS_여러_키() {
        run("set", "a", "1");
        run("set", "b", "1");
        expect(":3", "exists", "a", "b", "a", "none");
        expect(":2", "del", "a", "b", "none");
        expect(":0", "exists", "a");
    }

    @Test
    void EXPIRE_TTL() {
        expect(":-2", "ttl", "k");
        expect(":0", "expire", "k", "10");
        run("set", "k", "v");
        expect(":-1", "ttl", "k");
        expect(":1", "expire", "k", "10");
        clock.advanceMillis(1499);
        expect(":9", "ttl", "k"); // 8501ms → 반올림 9
        expect("-ERR value is not an integer or out of range", "expire", "k", "x");
        expect("-ERR invalid expire time in 'expire' command", "expire", "k", "9223372036854775807");
        expect(":1", "expire", "k", "-1");
        expect("$-1", "get", "k");
    }

    @Test
    void INCR() {
        expect(":1", "incr", "n");
        expect(":2", "incr", "n");
        run("set", "s", "abc");
        expect("-ERR value is not an integer or out of range", "incr", "s");
        run("set", "max", "9223372036854775807");
        expect("-ERR increment or decrement would overflow", "incr", "max");
        expect("$19\r\n9223372036854775807", "get", "max");
    }

    @Test
    void INCR는_기간을_유지한다() {
        run("set", "n", "5", "ex", "10");
        expect(":6", "incr", "n");
        expect(":10", "ttl", "n");
    }

    @Test
    void DBSIZE는_기한이_지났지만_안_지운_키도_센다() {
        expect(":0", "dbsize");
        run("set", "a", "1");
        run("set", "b", "2", "px", "100");
        expect(":2", "dbsize");
        clock.advanceMillis(200);
        expect(":2", "dbsize");
        expect("$-1", "get", "b");
        expect(":1", "dbsize");
        expect("-ERR wrong number of arguments for 'dbsize' command", "dbsize", "x");
    }
}
