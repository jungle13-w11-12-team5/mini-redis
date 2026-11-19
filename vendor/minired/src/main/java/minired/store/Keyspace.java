package minired.store;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * 저장소: 메인 테이블(키 → 값) + 만료 테이블(키 → 절대 만료 시각 ms) (Q5-1).
 *
 * 실행 스레드 1개만 만진다 (Q3-A). 락 없음.
 * 밖으로 공개된 함수는 모두 lookup()을 거친다. 만료 확인은 lookup()에만 있다 (Q5-2).
 */
public final class Keyspace {
    /** TTL 상한. 현재 시각(약 2^41 ms)에 더해도 long이 넘치지 않는다 */
    public static final long MAX_TTL_MILLIS = 1L << 52;

    // 표본 순찰 (Q5-B): 1회 20개, 지난 비율 25% 넘으면 반복, 시간 상한 25ms (100ms 주기의 25%)
    static final int SAMPLE_KEYS = 20;
    static final int SAMPLE_MAX_BUCKETS = SAMPLE_KEYS * 10;
    static final long EXPIRE_CYCLE_BUDGET_NANOS = 25_000_000L;
    // 유휴 rehash (B5): 100칸씩, 1회 최대 1ms
    static final int IDLE_REHASH_BUCKETS = 100;
    static final long IDLE_REHASH_BUDGET_NANOS = 1_000_000L;

    private final HashTable<byte[]> main;
    private final HashTable<Long> expires;
    private final Clock clock;
    private final RandomGenerator random;
    private final List<HashTable.Entry<Long>> sampleBuf = new ArrayList<>(SAMPLE_KEYS);

    public Keyspace(Clock clock) {
        this(clock, newRandomHasher(), new SplittableRandom());
    }

    Keyspace(Clock clock, SipHash hasher, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
        this.main = new HashTable<>(hasher);
        this.expires = new HashTable<>(hasher);
    }

    public byte[] get(byte[] key) {
        return lookup(key);
    }

    public boolean exists(byte[] key) {
        return lookup(key) != null;
    }

    /** 값을 쓰고 기간을 지운다 (Q5-3). */
    public void set(byte[] key, byte[] value) {
        lookup(key);
        main.put(key, value);
        expires.remove(key);
    }

    /** 값을 쓰고 지금부터 ttlMillis 뒤에 만료되게 한다. 0 < ttlMillis ≤ MAX_TTL_MILLIS. */
    public void setWithTtl(byte[] key, byte[] value, long ttlMillis) {
        checkTtl(ttlMillis);
        lookup(key);
        main.put(key, value);
        expires.put(key, clock.nowMillis() + ttlMillis);
    }

    /** 값만 바꾸고 기간은 그대로 둔다 (INCR). */
    public void replaceValue(byte[] key, byte[] value) {
        lookup(key);
        main.put(key, value);
    }

    public boolean delete(byte[] key) {
        if (lookup(key) == null) {
            return false;
        }
        removeBoth(key);
        return true;
    }

    /**
     * 기간을 ttlMillis로 정한다. ttlMillis ≤ 0이면 바로 지운다 (B12).
     * @return 키가 있었으면 true
     */
    public boolean expire(byte[] key, long ttlMillis) {
        if (lookup(key) == null) {
            return false;
        }
        if (ttlMillis <= 0) {
            removeBoth(key);
        } else {
            checkTtl(ttlMillis);
            expires.put(key, clock.nowMillis() + ttlMillis);
        }
        return true;
    }

    /** @return 남은 ms. 키 없음 -2, 기간 없음 -1 */
    public long ttlMillis(byte[] key) {
        if (lookup(key) == null) {
            return -2;
        }
        Long at = expires.get(key);
        return at == null ? -1 : Math.max(0, at - clock.nowMillis());
    }

    /** 이벤트 루프 타이머가 100ms마다 부른다 (B4-a). */
    /** 키 수 (DBSIZE). 기한이 지났지만 아직 안 지운 키도 센다 (Redis와 같음). 키 하나를 찾는 일이 아니라 키 찾기 함수를 거치지 않는다 */
    public long dbsize() {
        return main.size();
    }

    public void cron() {
        activeExpireCycle();
        main.shrinkIfSparse();    // 순찰로 지운 뒤에 본다 (B13)
        expires.shrinkIfSparse();
        idleRehash();
    }

    int capacity() { return main.capacity(); }

    int size() { return main.size(); }

    int expiresSize() { return expires.size(); }

    boolean isRehashing() { return main.isRehashing() || expires.isRehashing(); }

    /** 키 찾기 함수 (Q5-2). 만료됐으면 두 테이블에서 지우고 null. */
    private byte[] lookup(byte[] key) {
        byte[] v = main.get(key);
        if (v == null || expires.size() == 0) {
            return v;
        }
        Long at = expires.get(key);
        if (at != null && at <= clock.nowMillis()) {
            removeBoth(key);
            return null;
        }
        return v;
    }

    private void removeBoth(byte[] key) {
        main.remove(key);
        expires.remove(key);
    }

    /** 표본 순찰 (Q5-B). */
    void activeExpireCycle() {
        long start = clock.nanoTime();
        int sampled;
        int expired;
        do {
            sampleBuf.clear();
            expires.sample(SAMPLE_KEYS, SAMPLE_MAX_BUCKETS, random, sampleBuf);
            sampled = sampleBuf.size();
            expired = 0;
            long now = clock.nowMillis();
            for (HashTable.Entry<Long> e : sampleBuf) {
                if (e.value <= now) {
                    removeBoth(e.key);
                    expired++;
                }
            }
        } while (sampled > 0 && expired * 4 > sampled
                && clock.nanoTime() - start < EXPIRE_CYCLE_BUDGET_NANOS);
    }

    /** 요청이 없어도 rehash가 끝나게 한다 (B5). 메인 먼저, 끝났으면 만료 테이블. */
    private void idleRehash() {
        HashTable<?> t = main.isRehashing() ? main : expires;
        long start = clock.nanoTime();
        while (t.rehash(IDLE_REHASH_BUCKETS) && clock.nanoTime() - start < IDLE_REHASH_BUDGET_NANOS) {
            // 시간 상한까지 반복
        }
    }

    private static void checkTtl(long ttlMillis) {
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MILLIS) {
            throw new IllegalArgumentException("ttl out of range: " + ttlMillis);
        }
    }

    private static SipHash newRandomHasher() {
        SecureRandom r = new SecureRandom(); // Q4-2: 서버 시작마다 무작위 키
        return new SipHash(r.nextLong(), r.nextLong());
    }
}
