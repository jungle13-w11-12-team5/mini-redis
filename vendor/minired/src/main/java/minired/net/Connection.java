package minired.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import minired.command.CommandTable;
import minired.resp.ProtocolException;
import minired.resp.Reply;
import minired.resp.RespParser;

/**
 * 연결 하나의 상태 (흐름 지도 2 · 8). 실행 스레드만 만진다.
 *
 * 읽기 1회 최대 16KB, 그 안에서 완성된 명령만 실행 (B4-b).
 * 보낼 대기량이 1MB를 넘으면 읽기를 멈추고, 다 보내면 다시 읽는다 (ADR-0003).
 */
final class Connection {
    static final int READ_CHUNK = 16 << 10;
    static final int OUTPUT_LIMIT = 1 << 20;
    /** 덜 온 명령 하나를 담아 두는 상한 (bulk 16MiB 여러 개짜리 명령까지) */
    static final int INPUT_LIMIT = 64 << 20;
    private static final int SHRINK_ABOVE = 64 << 10;

    private final SocketChannel channel;
    private final SelectionKey key;
    private final CommandTable commands;
    private final RespParser parser = new RespParser(); // 덜 온 배열의 진행 상태를 기억 (B14)

    private byte[] in = new byte[READ_CHUNK];
    private int inLen;
    private byte[] out = new byte[READ_CHUNK];
    private int outStart;
    private int outEnd;
    private boolean paused;   // 배압으로 읽기 멈춤
    private boolean closing;  // 형식 오류: 남은 응답을 보내고 닫는다

    Connection(SocketChannel channel, SelectionKey key, CommandTable commands) {
        this.channel = channel;
        this.key = key;
        this.commands = commands;
    }

    /** 선택된 연결 처리. IOException이면 이벤트 루프가 이 연결만 닫는다. */
    void onReady() throws IOException {
        if (key.isWritable()) {
            flush();
        }
        if (key.isValid() && key.isReadable() && !paused && !closing) {
            read();
        }
    }

    private void read() throws IOException {
        if (in.length - inLen < READ_CHUNK) {
            in = grow(in, inLen + READ_CHUNK);
        }
        int n = channel.read(ByteBuffer.wrap(in, inLen, READ_CHUNK));
        if (n < 0) {
            close();
            return;
        }
        inLen += n;
        process();
        flush();
    }

    /** 버퍼 앞에서부터 완성된 명령을 실행한다 (흐름 지도 3 → 7). */
    private void process() {
        int pos = 0;
        try {
            while (!paused && pos < inLen) {
                RespParser.Parsed p = parser.parse(in, pos, inLen);
                if (p == null) {
                    break; // 덜 옴 (경로 C)
                }
                pos += p.consumed();
                if (p.args().length > 0) {
                    append(Reply.encode(commands.execute(p.args())));
                    paused = pending() > OUTPUT_LIMIT;
                }
            }
            if (inLen - pos > INPUT_LIMIT) {
                throw new ProtocolException("query buffer limit exceeded");
            }
        } catch (ProtocolException e) {
            append(Reply.encode(Reply.error("ERR Protocol error: " + e.getMessage())));
            closing = true;
            pos = inLen;
        }
        System.arraycopy(in, pos, in, 0, inLen - pos);
        inLen -= pos;
        if (inLen == 0 && in.length > SHRINK_ABOVE) {
            in = new byte[READ_CHUNK];
        }
    }

    /** 보낼 수 있는 만큼 보낸다. 다 보냈고 멈춘 상태였으면 남은 명령을 이어서 실행한다. */
    private void flush() throws IOException {
        while (true) {
            while (pending() > 0) {
                int n = channel.write(ByteBuffer.wrap(out, outStart, pending()));
                if (n == 0) {
                    break; // 소켓 송신 버퍼가 참 → OP_WRITE를 기다린다
                }
                outStart += n;
            }
            if (pending() > 0) {
                break;
            }
            outStart = outEnd = 0;
            if (out.length > SHRINK_ABOVE) {
                out = new byte[READ_CHUNK];
            }
            if (closing) {
                close();
                return;
            }
            if (!paused) {
                break;
            }
            paused = false;
            process(); // 멈춘 동안 버퍼에 남은 명령. 새 읽기 이벤트는 오지 않을 수 있다
        }
        int ops = (paused || closing ? 0 : SelectionKey.OP_READ) | (pending() > 0 ? SelectionKey.OP_WRITE : 0);
        key.interestOps(ops);
    }

    private void append(byte[] bytes) {
        if (out.length - outEnd < bytes.length) {
            int live = pending();
            byte[] next = out.length - live >= bytes.length ? out : new byte[Math.max(out.length * 2, live + bytes.length)];
            System.arraycopy(out, outStart, next, 0, live);
            out = next;
            outStart = 0;
            outEnd = live;
        }
        System.arraycopy(bytes, 0, out, outEnd, bytes.length);
        outEnd += bytes.length;
    }

    private int pending() {
        return outEnd - outStart;
    }

    void close() {
        key.cancel();
        try {
            channel.close();
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
    }

    private static byte[] grow(byte[] b, int min) {
        byte[] next = new byte[Math.max(b.length * 2, min)];
        System.arraycopy(b, 0, next, 0, b.length);
        return next;
    }
}
