package minired.resp;

import java.nio.charset.StandardCharsets;

/** 명령 처리 결과 (흐름 지도 6 → 7). RespWriter가 바이트로 바꾼다. */
public sealed interface Reply {
    record Simple(String text) implements Reply { }
    record Error(String message) implements Reply { }
    record Int(long value) implements Reply { }
    record Bulk(byte[] data) implements Reply { }
    record Nil() implements Reply { }

    Reply OK = new Simple("OK");
    Reply PONG = new Simple("PONG");
    Reply NIL = new Nil();

    /** message는 "ERR ..."처럼 에러 종류로 시작한다. */
    static Reply error(String message) { return new Error(message); }

    static Reply integer(long v) { return new Int(v); }

    static Reply bulk(byte[] data) { return data == null ? NIL : new Bulk(data); }

    /** 응답 바이트로 바꾼다 (흐름 지도 7). */
    static byte[] encode(Reply r) {
        return switch (r) {
            case Simple s -> line('+', s.text());
            case Error e -> line('-', e.message());
            case Int i -> line(':', Long.toString(i.value()));
            case Nil n -> "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
            case Bulk b -> {
                byte[] head = ("$" + b.data().length + "\r\n").getBytes(StandardCharsets.US_ASCII);
                byte[] out = new byte[head.length + b.data().length + 2];
                System.arraycopy(head, 0, out, 0, head.length);
                System.arraycopy(b.data(), 0, out, head.length, b.data().length);
                out[out.length - 2] = '\r';
                out[out.length - 1] = '\n';
                yield out;
            }
        };
    }

    private static byte[] line(char type, String text) {
        // 한 줄 응답 안에 줄바꿈이 있으면 규격이 깨진다
        return (type + text.replace('\r', ' ').replace('\n', ' ') + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
