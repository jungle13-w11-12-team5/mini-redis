package demo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** 조립 + 실행. 의존 방향: Api → UserCache → (Kv, Db). UserCache는 HTTP를 모른다. */
public final class Main {
    public static void main(String[] args) throws Exception {
        // DBMS(cpp-httplib)는 쉬는 keep-alive 연결을 5초 뒤 닫는다. 그보다 먼저 클라이언트가 버려야
        // 닫힌 연결에 POST를 보냈다가 실패하는 일이 없다 (POST는 자동 재시도 안 함)
        System.setProperty("jdk.httpclient.keepalive.timeout", "2");
        // 컨테이너가 다시 뜨면 IP가 바뀔 수 있고, 멈춘 동안의 "이름 없음"을 JVM이 10초 캐시하면 복구 뒤에도 우회가 계속된다
        java.security.Security.setProperty("networkaddress.cache.ttl", "1");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        Config cfg = Config.fromEnv();

        var kv = new JedisKv(cfg.redisHost(), cfg.redisPort(), cfg.redisTimeoutMs());
        var db = new HttpDb(cfg.dbUrl(), cfg.dbMaxConcurrency());
        var cache = new UserCache(kv, db, cfg.ttlSec(), cfg.emptyTtlSec(), cfg.maxBytes());
        System.out.println("demo: 시작 INCR — " + cache.bumpOnStart());

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port()), 1024);
        new Api(cache, kv, new Console(kv, db), cfg).mount(server);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("demo: :" + cfg.port() + " console=" + cfg.console() + " testHooks=" + cfg.testHooks()
                + " db≤" + cfg.dbMaxConcurrency() + " ttl=" + cfg.ttlSec() + "s");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            kv.close();
        }));
    }
}
