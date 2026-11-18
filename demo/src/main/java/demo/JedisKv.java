package demo;

import java.time.Duration;
import java.util.Locale;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.SetParams;
import java.util.function.Function;

/**
 * Jedis 연결 풀. 연결 · 읽기 · 풀 대기 모두 timeoutMs(기본 50ms)에서 끊는다 (Q21).
 * 차단기: 연결 실패가 나면 OPEN_MS 동안 레디스를 부르지 않고 바로 실패 → 호출 쪽이 DB로 우회.
 * (컨테이너가 멈추면 이름 조회 자체가 1초 넘게 걸려 50ms 타임아웃이 안 먹는다 — e2e에서 1.5초 관측)
 */
final class JedisKv implements Kv, AutoCloseable {
    static final long OPEN_MS = 1000;
    private final JedisPool pool;
    private volatile long openUntil;

    JedisKv(String host, int port, int timeoutMs) {
        var poolCfg = new GenericObjectPoolConfig<Jedis>();
        poolCfg.setMaxTotal(128);
        poolCfg.setMaxIdle(128);
        poolCfg.setMaxWait(Duration.ofMillis(timeoutMs));
        poolCfg.setJmxEnabled(false);
        var clientCfg = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMs)
                .socketTimeoutMillis(timeoutMs)
                // 미니 레디스에는 CLIENT 명령이 없다
                .clientSetInfoConfig(ClientSetInfoConfig.DISABLED)
                .build();
        pool = new JedisPool(poolCfg, new HostAndPort(host, port), clientCfg);
    }

    @Override public String get(String key) {
        return call(j -> j.get(key));
    }

    @Override public void setex(String key, int seconds, String value) {
        // 미니 레디스에는 SETEX가 없다 → SET key value EX n
        call(j -> j.set(key, value, SetParams.setParams().ex(seconds)));
    }

    @Override public long incr(String key) {
        return call(j -> j.incr(key));
    }

    @Override public long del(String key) {
        return call(j -> j.del(key));
    }

    @Override public long dbsize() {
        return call(Jedis::dbSize);
    }

    @Override public Object command(String name, String... args) {
        var cmd = Protocol.Command.valueOf(name.toUpperCase(Locale.ROOT));
        return call(j -> j.sendCommand(cmd, args));
    }

    private <T> T call(Function<Jedis, T> op) {
        if (System.currentTimeMillis() < openUntil) throw new IllegalStateException("레디스 차단 중 (최근 연결 실패)");
        try (Jedis j = pool.getResource()) {
            return op.apply(j);
        } catch (JedisConnectionException e) {
            openUntil = System.currentTimeMillis() + OPEN_MS;
            throw e;
        }
    }

    @Override public void close() {
        pool.close();
    }
}
