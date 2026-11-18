package demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputTest {
    static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void nameRules() throws Exception {
        assertEquals("kim", Sql.name(u("kim")));
        assertEquals("김민수", Sql.name(u("김민수")));                         // 9바이트
        assertEquals("1234567890123456", Sql.name(u("1234567890123456"))); // 경계 16
        assertThrows(Sql.BadInput.class, () -> Sql.name(u("12345678901234567")));
        assertThrows(Sql.BadInput.class, () -> Sql.name(u("김민수김민수")));   // 6글자 18바이트
        assertThrows(Sql.BadInput.class, () -> Sql.name(new byte[] {(byte) 0xff, 'a'}));
        assertThrows(Sql.BadInput.class, () -> Sql.name(new byte[] {(byte) 0xea, (byte) 0xb9}));   // 잘린 한글
        assertThrows(Sql.BadInput.class, () -> Sql.name(u("a\nb")));
        assertThrows(Sql.BadInput.class, () -> Sql.name(new byte[0]));
        assertThrows(Sql.BadInput.class, () -> Sql.name(null));
    }

    @Test
    void quoteEscapes() {
        assertEquals("'o''neil'", Sql.quote("o'neil"));
        assertEquals("''' OR ''1''=''1'", Sql.quote("' OR '1'='1"));
    }

    @Test
    void idRules() throws Exception {
        assertEquals(7, Sql.id("7"));
        assertThrows(Sql.BadInput.class, () -> Sql.id("0"));
        assertThrows(Sql.BadInput.class, () -> Sql.id("-1"));
        assertThrows(Sql.BadInput.class, () -> Sql.id("1 OR 1=1"));
        assertThrows(Sql.BadInput.class, () -> Sql.id("99999999999999999999"));
    }

    @Test
    void queryDecodingKeepsRawBytes() throws Exception {
        var q = Query.parse("name=%EA%B9%80&x=a+b");
        assertArrayEquals(u("김"), q.get("name"));
        assertArrayEquals(u("a b"), q.get("x"));
        assertArrayEquals(new byte[] {(byte) 0xff}, Query.parse("name=%FF").get("name"));
        assertThrows(Sql.BadInput.class, () -> Query.parse("name=%E"));
        assertThrows(Sql.BadInput.class, () -> Query.parse("name=%ZZ"));
    }

    @Test
    void ndjsonOkOnlyOnLastLine() {
        assertTrue(new DbResult(200, "{\"columns\":[]}\n{\"ok\":true,\"rowCount\":0}\n").selectOk());
        assertFalse(new DbResult(200, "{\"columns\":[]}\n[1]\n{\"ok\":false,\"error\":{}}\n").selectOk());
        assertFalse(new DbResult(404, "{\"ok\":false}").selectOk());
        assertFalse(new DbResult(200, "{\"columns\":[]}\n[1]\n").selectOk());   // 잘린 응답
        assertEquals(3, new DbResult(200, "x\n{\"ok\":true,\"rowCount\":3}").rowCount());
        assertEquals(1, new DbResult(200, "{\"ok\":true,\"affectedRows\":[1]}").firstAffected());
    }

    @Test
    void consoleTokenize() {
        assertEquals(List.of("SET", "k", "a b"), Console.tokenize("SET k \"a b\""));
        assertEquals(List.of("GET", "users:ver"), Console.tokenize("  GET   users:ver "));
        assertThrows(IllegalArgumentException.class, () -> Console.tokenize("GET \"x"));
    }

    @Test
    void jsonIsAsciiSafe() {
        assertEquals("\"\\uae40\\\"\\n\"", Json.ascii("김\"\n"));
        assertEquals("\"김\"", Json.str("김"));
    }
}
