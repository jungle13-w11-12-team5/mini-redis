package demo;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 입력 → SQL 문자열 (Q27). DBMS는 문자열로만 받으므로 입구에서 막는다:
 * UTF-8 엄격 검사 → 1..16 바이트(VARCHAR(16)) → 제어 문자 거부 → ' 를 '' 로.
 * 근본 해법(바인딩 · AST 전달)은 DBMS 쪽 리팩터링 후보, 범위 밖.
 */
final class Sql {
    static final int NAME_MAX_BYTES = 16;

    private Sql() {}

    static final class BadInput extends Exception {
        BadInput(String message) {
            super(message);
        }
    }

    /** 퍼센트 디코딩이 끝난 바이트 → 검사된 이름 */
    static String name(byte[] raw) throws BadInput {
        if (raw == null || raw.length == 0) throw new BadInput("name이 비었음");
        if (raw.length > NAME_MAX_BYTES) throw new BadInput("name은 UTF-8 " + NAME_MAX_BYTES + "바이트 이하 (지금 " + raw.length + ")");
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new BadInput("name이 올바른 UTF-8이 아님");
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) throw new BadInput("name에 제어 문자");
        }
        return s;
    }

    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static long id(String s) throws BadInput {
        try {
            long v = Long.parseLong(s);
            if (v < 1) throw new BadInput("id는 1 이상");
            return v;
        } catch (NumberFormatException e) {
            throw new BadInput("id는 정수");
        }
    }

    static long age(byte[] raw) throws BadInput {
        String s = new String(raw, StandardCharsets.UTF_8);
        try {
            long v = Long.parseLong(s);
            if (v < 0 || v > 200) throw new BadInput("age는 0..200");
            return v;
        } catch (NumberFormatException e) {
            throw new BadInput("age는 정수");
        }
    }
}
