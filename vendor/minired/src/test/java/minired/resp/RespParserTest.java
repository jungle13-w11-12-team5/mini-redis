package minired.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespParserTest {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static RespParser.Parsed parse(String s) throws ProtocolException {
        byte[] buf = b(s);
        return new RespParser().parse(buf, 0, buf.length);
    }

    private static String str(byte[] a) { return new String(a, StandardCharsets.UTF_8); }

    @Test
    void 배열_명령() throws Exception {
        RespParser.Parsed p = parse("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n");
        assertEquals(3, p.args().length);
        assertEquals("SET", str(p.args()[0]));
        assertEquals("hello", str(p.args()[2]));
        assertEquals(b("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n").length, p.consumed());
    }

    @Test
    void 값_안의_CRLF도_길이로_읽는다() throws Exception {
        RespParser.Parsed p = parse("*1\r\n$4\r\na\r\nb\r\n");
        assertEquals("a\r\nb", str(p.args()[0]));
    }

    @Test
    void 덜_온_명령은_어디서_잘려도_null() throws Exception {
        byte[] full = b("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");
        RespParser parser = new RespParser(); // 한 개를 계속 써서 이어 훑기도 같이 확인
        for (int cut = 0; cut < full.length; cut++) {
            assertNull(parser.parse(full, 0, cut), "cut=" + cut);
        }
        RespParser.Parsed p = parser.parse(full, 0, full.length);
        assertEquals(full.length, p.consumed());
        assertEquals("key", str(p.args()[1]));
    }

    @Test
    void 파이프라인은_첫_명령만_소비하고_다음_명령은_처음부터_읽는다() throws Exception {
        byte[] buf = b("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n");
        RespParser parser = new RespParser();
        RespParser.Parsed p = parser.parse(buf, 0, buf.length);
        assertEquals(14, p.consumed());
        RespParser.Parsed q = parser.parse(buf, p.consumed(), buf.length);
        assertEquals("GET", str(q.args()[0]));
        assertEquals(buf.length - 14, q.consumed());
    }

    @Test
    void 버퍼를_앞으로_당겨도_이어_훑기가_맞다() throws Exception {
        // Connection처럼: 앞 명령을 소비한 뒤 남은 바이트를 0으로 당기고 다시 부른다
        byte[] cmd = b("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n");
        RespParser parser = new RespParser();
        byte[] buf = new byte[64];
        int half = 20;
        System.arraycopy(cmd, 0, buf, 10, half);
        assertNull(parser.parse(buf, 10, 10 + half));
        byte[] moved = new byte[64];
        System.arraycopy(cmd, 0, moved, 0, cmd.length);
        RespParser.Parsed p = parser.parse(moved, 0, cmd.length);
        assertEquals("hello", str(p.args()[2]));
    }

    @Test
    void 인자_100만_개가_16KB씩_와도_훑는_양이_받은_양에_비례한다() throws Exception {
        int n = RespParser.MAX_ARGS;
        StringBuilder sb = new StringBuilder("*").append(n).append("\r\n");
        for (int i = 0; i < n; i++) sb.append("$1\r\nx\r\n");
        byte[] full = b(sb.toString()); // 약 7MB
        RespParser parser = new RespParser();
        long start = System.nanoTime();
        RespParser.Parsed p = null;
        for (int end = 16 << 10; p == null; end = Math.min(end + (16 << 10), full.length)) {
            p = parser.parse(full, 0, end);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(n, p.args().length);
        // 2026-11-18 이 PC 실측: 처음부터 다시 훑기 4.1초, 이어 훑기 0.09초. 기준 1초 = 되돌아가면 실패
        assertTrue(ms < 1000, "took " + ms + "ms");
    }

    @Test
    void inline_명령() throws Exception {
        RespParser.Parsed p = parse("SET  k\tv\r\n");
        assertEquals(3, p.args().length);
        assertEquals("v", str(p.args()[2]));
        assertEquals(0, parse("\r\n").args().length);
        assertNull(parse("PING"));
    }

    @Test
    void 깨진_형식은_예외() {
        ProtocolException e = assertThrows(ProtocolException.class, () -> parse("*1\r\n#3\r\nGET\r\n"));
        assertEquals("expected '$', got '#'", e.getMessage());
        assertThrows(ProtocolException.class, () -> parse("*x\r\n"));
        assertThrows(ProtocolException.class, () -> parse("*1\r\n$-1\r\n"));
        assertThrows(ProtocolException.class, () -> parse("*1\r\n$3\r\nGETX\r\n"));
    }

    @Test
    void 상한을_넘으면_예외() {
        assertThrows(ProtocolException.class, () -> parse("*" + (RespParser.MAX_ARGS + 1) + "\r\n"));
        assertThrows(ProtocolException.class, () -> parse("*1\r\n$" + (RespParser.MAX_BULK + 1) + "\r\n"));
        String longLine = "x".repeat(RespParser.MAX_INLINE + 1);
        assertThrows(ProtocolException.class, () -> parse(longLine));
    }

    @Test
    void 빈_배열은_건너뛸_명령() throws Exception {
        assertEquals(0, parse("*0\r\n").args().length);
        assertEquals(0, parse("*-1\r\n").args().length);
    }

    @Test
    void Redis_정수_규칙() {
        assertEquals(0, RespParser.parseLong(b("0")));
        assertEquals(-42, RespParser.parseLong(b("-42")));
        assertEquals(Long.MAX_VALUE, RespParser.parseLong(b("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, RespParser.parseLong(b("-9223372036854775808")));
        for (String bad : new String[] {"", "-", "+1", "01", "-0", " 1", "1 ", "1.0", "9223372036854775808"}) {
            assertThrows(NumberFormatException.class, () -> RespParser.parseLong(b(bad)), bad);
        }
    }

    @Test
    void 응답_인코딩() {
        assertArrayEquals(b("+OK\r\n"), Reply.encode(Reply.OK));
        assertArrayEquals(b("-ERR x\r\n"), Reply.encode(Reply.error("ERR x")));
        assertArrayEquals(b(":-3\r\n"), Reply.encode(Reply.integer(-3)));
        assertArrayEquals(b("$-1\r\n"), Reply.encode(Reply.NIL));
        assertArrayEquals(b("$2\r\nhi\r\n"), Reply.encode(Reply.bulk(b("hi"))));
        assertArrayEquals(b("-ERR a  b\r\n"), Reply.encode(Reply.error("ERR a\r\nb"))); // 줄바꿈은 공백으로
    }
}
