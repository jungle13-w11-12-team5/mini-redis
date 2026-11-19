package minired.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import minired.command.CommandTable;
import minired.store.Clock;

/**
 * 단일 스레드 이벤트 루프 (Q3-A, 흐름 지도 1).
 * run()을 부른 스레드가 실행 스레드다. 저장소는 이 스레드만 만진다.
 *
 * 타이머 (B4-a): select(다음 타이머까지 남은 ms)로 기다리고, 기한이 되면 cron을 부른다.
 */
public final class EventLoop {
    static final long CRON_PERIOD_NANOS = 100_000_000L; // 초당 10회 (Q5-B)

    private final Selector selector;
    private final ServerSocketChannel server;
    private final CommandTable commands;
    private final Clock clock;
    private final Runnable cron;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean running = true;

    public EventLoop(InetSocketAddress bind, CommandTable commands, Clock clock, Runnable cron) throws IOException {
        this.commands = commands;
        this.clock = clock;
        this.cron = cron;
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        server.bind(bind);
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int port() {
        return server.socket().getLocalPort();
    }

    public void run() {
        long nextCron = clock.nanoTime() + CRON_PERIOD_NANOS;
        try {
            while (running) {
                long wait = nextCron - clock.nanoTime();
                if (wait <= 0) {
                    selector.selectNow();
                } else {
                    selector.select((wait + 999_999) / 1_000_000); // 올림: 0이면 무한 대기가 된다
                }
                handleSelected();
                long now = clock.nanoTime();
                if (now >= nextCron) {
                    runCron();
                    nextCron = now + CRON_PERIOD_NANOS;
                }
            }
        } catch (IOException e) {
            System.out.println("[minired] event loop stopped: " + e);
        } finally {
            closeAll();
            stopped.countDown();
        }
    }

    /** 다른 스레드에서 불러도 된다 (종료 훅, 테스트). */
    public void stop() {
        running = false;
        selector.wakeup();
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    private void handleSelected() {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            if (key.isAcceptable()) {
                accept();
                continue;
            }
            Connection c = (Connection) key.attachment();
            try {
                c.onReady();
            } catch (IOException e) {
                c.close(); // 이 연결만 정리 (흐름 지도 8)
            }
        }
    }

    private void accept() {
        try {
            SocketChannel ch = server.accept();
            if (ch == null) {
                return;
            }
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
            key.attach(new Connection(ch, key, commands));
        } catch (IOException e) {
            System.out.println("[minired] accept failed: " + e);
        }
    }

    private void runCron() {
        try {
            cron.run();
        } catch (RuntimeException e) {
            System.out.println("[minired] cron failed: " + e);
        }
    }

    private void closeAll() {
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof Connection c) {
                c.close();
            }
        }
        try {
            server.close();
            selector.close();
        } catch (IOException ignored) {
            // 종료 중
        }
    }
}
