package minired.store;

import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * byte[] 키 해시 테이블: 체이닝 + 점진적 rehash (Q4-C).
 *
 * 불변식: rehash 중(t1 != null) 키 하나는 t0 · t1 중 정확히 한 곳에만 있다.
 * t0의 [0, rehashIdx) 칸은 이미 비었다. 삽입은 t1에만 한다.
 */
final class HashTable<V> {
    static final int INITIAL_SIZE = 4;
    /** 조회 · 삽입 · 삭제 1회당 옮기는 칸 수 (B5) */
    private static final int STEP_BUCKETS = 1;
    /** 빈 칸 건너뛰기 상한 = 옮길 칸 수 × 10 (B5) */
    private static final int EMPTY_VISITS_PER_BUCKET = 10;

    static final class Entry<V> {
        final byte[] key;
        final long hash;
        V value;
        Entry<V> next;

        Entry(byte[] key, long hash, V value, Entry<V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private final SipHash hasher;
    private Entry<V>[] t0;
    private Entry<V>[] t1;      // rehash 중일 때만 null이 아님
    private int rehashIdx = -1; // t0에서 다음에 옮길 칸
    private int size;

    HashTable(SipHash hasher) {
        this.hasher = hasher;
        this.t0 = newArray(INITIAL_SIZE);
    }

    int size() { return size; }

    boolean isRehashing() { return t1 != null; }

    int capacity() { return t0.length + (t1 == null ? 0 : t1.length); }

    V get(byte[] key) {
        stepIfRehashing();
        Entry<V> e = find(key, hasher.hash(key));
        return e == null ? null : e.value;
    }

    /** 있으면 값을 바꾸고 옛 값을, 없으면 넣고 null을 돌려준다. */
    V put(byte[] key, V value) {
        stepIfRehashing();
        long h = hasher.hash(key);
        Entry<V> e = find(key, h);
        if (e != null) {
            V old = e.value;
            e.value = value;
            return old;
        }
        Entry<V>[] t = isRehashing() ? t1 : t0;
        int i = index(h, t);
        t[i] = new Entry<>(key, h, value, t[i]);
        size++;
        if (!isRehashing() && size >= t0.length) { // Q4-3: 키 수 ≥ 칸 수면 2배
            t1 = newArray(t0.length * 2);
            rehashIdx = 0;
        }
        return null;
    }

    V remove(byte[] key) {
        stepIfRehashing();
        long h = hasher.hash(key);
        V v = removeFrom(t0, key, h);
        if (v == null && isRehashing()) {
            v = removeFrom(t1, key, h);
        }
        if (v != null) {
            size--;
        }
        return v;
    }

    /**
     * 키 수가 칸 수의 10% 밑이면 축소를 시작한다 (B13). 옮기기는 확장과 같은 점진적 rehash.
     * 새 크기 = 키 수 × 2 이상인 가장 작은 2의 거듭제곱 → 부하율 0.25~0.5에서 시작해 바로 다시 커지지 않는다.
     * @return 축소를 시작했으면 true
     */
    boolean shrinkIfSparse() {
        if (isRehashing() || t0.length <= INITIAL_SIZE || (long) size * 10 >= t0.length) {
            return false;
        }
        int target = INITIAL_SIZE;
        while (target < size * 2) {
            target <<= 1;
        }
        t1 = newArray(target);
        rehashIdx = 0;
        return true;
    }

    /**
     * 최대 buckets칸을 t0 → t1로 옮긴다. 빈 칸은 buckets × 10칸까지만 건너뛴다.
     * @return rehash가 아직 남았으면 true
     */
    boolean rehash(int buckets) {
        if (!isRehashing()) {
            return false;
        }
        int emptyVisits = buckets * EMPTY_VISITS_PER_BUCKET;
        while (buckets > 0 && rehashIdx < t0.length) {
            Entry<V> e = t0[rehashIdx];
            if (e == null) {
                rehashIdx++;
                if (--emptyVisits == 0) {
                    break;
                }
                continue;
            }
            while (e != null) {
                Entry<V> next = e.next;
                int i = index(e.hash, t1);
                e.next = t1[i];
                t1[i] = e;
                e = next;
            }
            t0[rehashIdx++] = null;
            buckets--;
        }
        if (rehashIdx == t0.length) {
            t0 = t1;
            t1 = null;
            rehashIdx = -1;
            return false;
        }
        return true;
    }

    /**
     * 무작위 시작 칸에서 연속으로 훑어 최대 count개를 out에 담는다 (B6).
     * 훑는 칸은 maxBuckets까지. 빈 칸이 많아도 1회 비용에 상한이 있다.
     */
    void sample(int count, int maxBuckets, RandomGenerator random, List<Entry<V>> out) {
        if (size == 0) {
            return;
        }
        // 살아 있는 칸 = [first, total). rehash 중이면 t0 뒤에 t1을 이어 붙이고, t0의 옮긴 앞쪽은 뺀다
        int total = capacity();
        int first = isRehashing() ? rehashIdx : 0;
        int live = total - first;
        int pos = first + random.nextInt(live);
        int limit = Math.min(maxBuckets, live); // 한 바퀴를 넘으면 같은 키를 두 번 담는다
        for (int visited = 0; visited < limit && out.size() < count; visited++) {
            Entry<V> e = pos < t0.length ? t0[pos] : t1[pos - t0.length];
            for (; e != null && out.size() < count; e = e.next) {
                out.add(e);
            }
            pos = pos + 1 == total ? first : pos + 1;
        }
    }

    private void stepIfRehashing() {
        if (isRehashing()) {
            rehash(STEP_BUCKETS);
        }
    }

    private Entry<V> find(byte[] key, long h) {
        Entry<V> e = findIn(t0, key, h);
        if (e == null && isRehashing()) {
            e = findIn(t1, key, h);
        }
        return e;
    }

    private static <V> Entry<V> findIn(Entry<V>[] t, byte[] key, long h) {
        for (Entry<V> e = t[index(h, t)]; e != null; e = e.next) {
            if (e.hash == h && Arrays.equals(e.key, key)) {
                return e;
            }
        }
        return null;
    }

    private static <V> V removeFrom(Entry<V>[] t, byte[] key, long h) {
        int i = index(h, t);
        Entry<V> prev = null;
        for (Entry<V> e = t[i]; e != null; prev = e, e = e.next) {
            if (e.hash == h && Arrays.equals(e.key, key)) {
                if (prev == null) {
                    t[i] = e.next;
                } else {
                    prev.next = e.next;
                }
                return e.value;
            }
        }
        return null;
    }

    private static int index(long h, Entry<?>[] t) {
        return (int) (h & (t.length - 1));
    }

    @SuppressWarnings("unchecked")
    private static <V> Entry<V>[] newArray(int n) {
        return (Entry<V>[]) new Entry[n];
    }
}
