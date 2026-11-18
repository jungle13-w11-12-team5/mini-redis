package minired;

import java.net.InetSocketAddress;
import minired.command.CommandTable;
import minired.command.Commands;
import minired.net.EventLoop;
import minired.store.Clock;
import minired.store.Keyspace;

/** 조립 + 실행. 사용법: java -jar minired.jar [--port N] (B9) */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        int port = 6379;
        if (args.length == 2 && args[0].equals("--port")) {
            port = Integer.parseInt(args[1]);
        } else if (args.length != 0) {
            System.out.println("usage: minired [--port N]");
            System.exit(1);
        }

        Clock clock = Clock.system();
        Keyspace ks = new Keyspace(clock);
        EventLoop loop = new EventLoop(new InetSocketAddress(port), new CommandTable(new Commands(ks)), clock, ks::cron);

        // SIGTERM → 루프를 깨워 멈추고 소켓을 닫는다. Phase 1엔 저장할 것이 없다
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[minired] shutting down");
            loop.stop();
            try {
                loop.awaitStop();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));

        System.out.println("[minired] listening on port " + loop.port());
        loop.run();
    }
}
