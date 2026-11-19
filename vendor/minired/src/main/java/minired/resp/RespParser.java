package minired.resp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 프레이밍 + 파서 (흐름 지도 3 · 4). 버퍼 앞부분에서 명령 1개를 꺼낸다.
 * 덜 왔으면 null → 연결 버퍼가 다음 바이트를 기다린다 (경로 C).
 *
 * 연결마다 1개. 덜 온 배열은 "확인한 bulk 수 · 다음 위치"를 기억해 다음 호출에서 이어 훑는다 (B14).
 * 호출하는 쪽은 덜 온 명령의 시작을 같은 명령 시작으로 다시 넘긴다 (버퍼를 앞으로 당겨도 상대 위치는 같다).
 */
public final class RespParser {
    // B8 상한
    public static final int MAX_ARGS = 1 << 20;
    public static final int MAX_BULK = 16 << 20;
    public static final int MAX_INLINE = 64 << 10;

    public record Parsed(byte[][] args, int consumed) { }

    private static final byte[][] NO_ARGS = new byte[0][];

    private int scannedBulks;  // 덜 온 배열에서 이미 다 온 것을 확인한 bulk 수
    private int scannedOffset; // 그다음 bulk의 시작 (명령 시작 기준)

    /**
     * buf[off, end)의 앞에서 명령 1개를 읽는다.
     * @return 덜 왔으면 null. 빈 명령은 args 길이 0
     */
    public Parsed parse(byte[] buf, int off, int end) throws ProtocolException {
        if (off >= end) {
            return null;
        }
        try {
            return buf[off] == '*' ? parseArray(buf, off, end) : parseInline(buf, off, end);
        } catch (ProtocolException e) {
            scannedBulks = 0; // 연결은 닫히지만 상태를 남기지 않는다
            throw e;
        }
    }

    /** RESP 배열: *N\r\n 뒤에 $len\r\n<bytes>\r\n × N. 다 왔는지 먼저 훑고, 다 왔을 때만 복사한다. */
    private Parsed parseArray(byte[] b, int off, int end) throws ProtocolException {
        int lineEnd = findCrlf(b, off + 1, end);
        if (lineEnd < 0) {
            if (end - off > MAX_INLINE) throw new ProtocolException("too big mbulk count string");
            return null;
        }
        long n = header(b, off + 1, lineEnd, MAX_ARGS, "invalid multibulk length");
        int first = lineEnd + 2;
        if (n <= 0) {
            return new Parsed(NO_ARGS, first - off);
        }
        // 훑을 때는 아무것도 만들지 않고, 지난번에 멈춘 곳에서 이어 간다
        int from = scannedBulks;
        int pos = from == 0 ? first : off + scannedOffset;
        int done = walkBulks(b, off, pos, end, from, (int) n, null);
        if (done < 0) {
            return null;
        }
        scannedBulks = 0;
        byte[][] args = new byte[(int) n][];
        walkBulks(b, off, first, end, 0, (int) n, args);
        return new Parsed(args, done - off);
    }

    /**
     * $len\r\n<bytes>\r\n를 from번째부터 n번째 전까지 걷는다.
     * out이 없으면 훑기만 하고, 다 온 bulk마다 진행 상태를 남긴다. out이 있으면 값을 복사해 담는다.
     * @return 끝난 위치, 덜 왔으면 -1
     */
    private int walkBulks(byte[] b, int off, int pos, int end, int from, int n, byte[][] out)
            throws ProtocolException {
        for (int i = from; i < n; i++) {
            if (pos >= end) return -1;
            if (b[pos] != '$') {
                throw new ProtocolException("expected '$', got '" + (char) b[pos] + "'");
            }
            int lineEnd = findCrlf(b, pos + 1, end);
            if (lineEnd < 0) {
                if (end - pos > MAX_INLINE) throw new ProtocolException("too big bulk count string");
                return -1;
            }
            long len = header(b, pos + 1, lineEnd, MAX_BULK, "invalid bulk length");
            if (len < 0) throw new ProtocolException("invalid bulk length");
            int data = lineEnd + 2;
            if ((long) data + len + 2 > end) return -1;
            int dataEnd = data + (int) len;
            if (b[dataEnd] != '\r' || b[dataEnd + 1] != '\n') {
                throw new ProtocolException("bulk length does not match data");
            }
            pos = dataEnd + 2;
            if (out != null) {
                out[i] = Arrays.copyOfRange(b, data, dataEnd);
            } else {
                scannedBulks = i + 1;
                scannedOffset = pos - off;
            }
        }
        return pos;
    }

    /** inline 명령: 공백으로 나눈 한 줄 (nc · telnet용). 따옴표는 지원하지 않는다. */
    private static Parsed parseInline(byte[] b, int off, int end) throws ProtocolException {
        int nl = -1;
        for (int i = off; i < end; i++) {
            if (b[i] == '\n') { nl = i; break; }
        }
        if (nl < 0) {
            if (end - off > MAX_INLINE) throw new ProtocolException("too big inline request");
            return null;
        }
        int lineEnd = nl > off && b[nl - 1] == '\r' ? nl - 1 : nl;
        List<byte[]> args = new ArrayList<>();
        int i = off;
        while (i < lineEnd) {
            while (i < lineEnd && (b[i] == ' ' || b[i] == '\t')) i++;
            int start = i;
            while (i < lineEnd && b[i] != ' ' && b[i] != '\t') i++;
            if (i > start) args.add(Arrays.copyOfRange(b, start, i));
        }
        return new Parsed(args.toArray(NO_ARGS), nl + 1 - off);
    }

    private static long header(byte[] b, int from, int to, long max, String error) throws ProtocolException {
        try {
            long v = parseLong(b, from, to);
            if (v > max) throw new ProtocolException(error);
            return v;
        } catch (NumberFormatException e) {
            throw new ProtocolException(error);
        }
    }

    /** \r\n의 \r 위치, 없으면 -1. */
    private static int findCrlf(byte[] b, int from, int end) {
        for (int i = from; i + 1 < end; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') return i;
        }
        return -1;
    }

    /**
     * Redis 정수 문자열 규칙: 부호는 '-'만, 앞자리 0 금지("0"만 허용), 공백 금지, 64비트 범위 (B7).
     * @throws NumberFormatException 규칙을 어기거나 범위를 넘으면
     */
    public static long parseLong(byte[] b, int from, int to) {
        int len = to - from;
        if (len <= 0 || len > 20) throw new NumberFormatException();
        if (len == 1 && b[from] == '0') return 0;
        boolean negative = b[from] == '-';
        int i = negative ? from + 1 : from;
        if (i >= to || b[i] < '1' || b[i] > '9') throw new NumberFormatException();
        long acc = 0; // 음수로 쌓아야 Long.MIN_VALUE까지 표현된다
        for (; i < to; i++) {
            int d = b[i] - '0';
            if (d < 0 || d > 9) throw new NumberFormatException();
            if (acc < (Long.MIN_VALUE + d) / 10) throw new NumberFormatException();
            acc = acc * 10 - d;
        }
        if (!negative) {
            if (acc == Long.MIN_VALUE) throw new NumberFormatException();
            return -acc;
        }
        return acc;
    }

    public static long parseLong(byte[] b) {
        return parseLong(b, 0, b.length);
    }
}
