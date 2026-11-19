package demo;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 쿼리 문자열 → 이름별 원본 바이트. URLDecoder는 깨진 UTF-8을 U+FFFD로 조용히 바꾸므로 쓰지 않는다 —
 * 바이트로 두고 검사는 Sql.name이 엄격하게 한다 (Q27).
 */
final class Query {
    private Query() {}

    static Map<String, byte[]> parse(String rawQuery) throws Sql.BadInput {
        var out = new HashMap<String, byte[]>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = new String(decode(eq < 0 ? part : part.substring(0, eq)), java.nio.charset.StandardCharsets.UTF_8);
            byte[] v = eq < 0 ? new byte[0] : decode(part.substring(eq + 1));
            out.putIfAbsent(k, v);
        }
        return out;
    }

    static byte[] decode(String s) throws Sql.BadInput {
        var b = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') b.write(' ');
            else if (c == '%') {
                if (i + 2 >= s.length()) throw new Sql.BadInput("퍼센트 인코딩이 잘림");
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new Sql.BadInput("퍼센트 인코딩이 잘못됨");
                b.write(hi * 16 + lo);
                i += 2;
            } else if (c > 0x7f) {
                throw new Sql.BadInput("쿼리 문자열에 인코딩 안 된 문자");
            } else b.write(c);
        }
        return b.toByteArray();
    }
}
