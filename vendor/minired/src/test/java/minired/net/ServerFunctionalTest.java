package minired.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import minired.command.CommandTable;
import minired.command.Commands;
import minired.store.Clock;
import minired.store.Keyspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

/** 기능 테스트: 진짜 클라이언트(Jedis)와 원시 바이트로 서버에 붙는다 (ADR-0002). */
class ServerFunctionalTest {
    private EventLoop loop;
    private Thread thread;
    private int port;

    @BeforeEach
    void start() throws IOException {
        Clock clock = Clock.system();
        Keyspace ks = new Keyspace(clock);
        loop = new EventLoop(new InetSocketAddress("127.0.0.1", 0), new CommandTable(new Commands(ks)), clock, ks::cron);
        port = loop.port();
        thread = new Thread(loop::run, "minired-loop");
        thread.start();
    }

    @AfterEach
    void stop() throws InterruptedException {
        loop.stop();
        loop.awaitStop();
    }

    private Jedis jedis() {
        return new Jedis("127.0.0.1", port);
    }

    // ---- 진짜 클라이언트 ----

    @Test
    void 기본_명령() {
        try (Jedis j = jedis()) {
            assertEquals("PONG", j.ping());
            assertEquals("OK", j.set("k", "v"));
            assertEquals("v", j.get("k"));
            assertNull(j.get("none"));
            assertEquals(2, j.exists("k", "k"));
            assertEquals(1, j.del("k", "none"));
            assertNull(j.set("k", "v", SetParams.setParams().xx()));
            assertEquals("OK", j.set("k", "v", SetParams.setParams().nx().ex(100)));
            assertTrue(j.ttl("k") > 98);
            assertEquals(1, j.expire("k", 50));
            assertEquals(1, j.incr("n"));
            assertEquals(2, j.dbSize());
        }
    }

    @Test
    void 에러는_응답으로_오고_연결은_계속_쓸_수_있다() {
        try (Jedis j = jedis()) {
            j.set("s", "abc");
            JedisDataException e = assertThrows(JedisDataException.class, () -> j.incr("s"));
            assertEquals("ERR value is not an integer or out of range", e.getMessage());
            assertEquals("PONG", j.ping());
        }
    }

    @Test
    void 기간이_지나면_사라진다() throws InterruptedException {
        try (Jedis j = jedis()) {
            j.set("k", "v", SetParams.setParams().px(100));
            Thread.sleep(150);
            assertNull(j.get("k"));
            assertEquals(-2, j.ttl("k"));
        }
    }

    @Test
    void 동시_접속_N개가_M번씩_INCR해도_합이_맞다() throws Exception {
        int clients = 20;
        int times = 500;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            fs.add(pool.submit(() -> {
                try (Jedis j = jedis()) {
                    for (int i = 0; i < times; i++) j.incr("counter");
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        try (Jedis j = jedis()) {
            assertEquals(String.valueOf(clients * times), j.get("counter"));
        }
    }

    // ---- 원시 바이트 ----

    private Socket raw() throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        return s;
    }

    private static void send(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
    }

    private static String readExactly(Socket s, int n) throws IOException {
        byte[] buf = s.getInputStream().readNBytes(n);
        return new String(buf, StandardCharsets.UTF_8);
    }

    @Test
    void 한_바이트씩_조각으로_와도_명령이_된다() throws Exception {
        try (Socket s = raw()) {
            for (char ch : "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$2\r\nhi\r\n".toCharArray()) {
                send(s, String.valueOf(ch));
                Thread.sleep(1);
            }
            assertEquals("+OK\r\n", readExactly(s, 5));
        }
    }

    @Test
    void 파이프라인_응답은_순서대로() throws Exception {
        try (Socket s = raw()) {
            send(s, "SET a 1\r\nINCR a\r\nGET a\r\nPING\r\n");
            String expected = "+OK\r\n:2\r\n$1\r\n2\r\n+PONG\r\n";
            assertEquals(expected, readExactly(s, expected.length()));
        }
    }

    @Test
    void 형식이_깨지면_에러_후_그_연결만_닫힌다() throws Exception {
        try (Socket bad = raw(); Socket good = raw()) {
            send(bad, "*1\r\n#3\r\nGET\r\n");
            InputStream in = bad.getInputStream();
            String reply = new String(in.readAllBytes(), StandardCharsets.UTF_8); // 서버가 닫으면 EOF
            assertEquals("-ERR Protocol error: expected '$', got '#'\r\n", reply);
            send(good, "PING\r\n");
            assertEquals("+PONG\r\n", readExactly(good, 7));
        }
    }

    @Test
    void 응답을_안_읽는_클라이언트가_있어도_다른_연결은_돌고_응답은_다_온다() throws Exception {
        int valueSize = 512 * 1024;
        int requests = 64; // 응답 합계 32MB ≫ 송신 상한 1MB
        try (Socket slow = raw(); Socket other = raw(); Jedis j = jedis()) {
            j.set("big", "x".repeat(valueSize));
            send(slow, "GET big\r\n".repeat(requests)); // 보내기만 하고 안 읽음

            Thread.sleep(200);
            send(other, "PING\r\n");
            assertEquals("+PONG\r\n", readExactly(other, 7));

            String head = "$" + valueSize + "\r\n";
            long expected = (long) requests * (head.length() + valueSize + 2);
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            InputStream in = slow.getInputStream();
            while (got.size() < expected) {
                int n = in.read(buf);
                if (n < 0) break;
                got.write(buf, 0, n);
            }
            assertEquals(expected, got.size());
        }
    }
}
