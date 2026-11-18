package minired.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HashTableTest {
    private final HashTable<byte[]> t = new HashTable<>(new SipHash(1, 2));

    private static byte[] k(int i) { return ("key:" + i).getBytes(); }

    /** 두 배열을 모두 훑은 항목 수. 키가 두 곳에 있으면 size()보다 커진다. */
    private int entriesInArrays() {
        List<HashTable.Entry<byte[]>> all = new ArrayList<>();
        t.sample(Integer.MAX_VALUE, t.capacity(), new SplittableRandom(0), all);
        return all.size();
    }

    @Test
    void 저장_조회_덮어쓰기_삭제() {
        assertNull(t.put(k(1), "a".getBytes()));
        assertArrayEquals("a".getBytes(), t.get(k(1)));
        assertArrayEquals("a".getBytes(), t.put(k(1), "b".getBytes()));
        assertArrayEquals("b".getBytes(), t.get(k(1)));
        assertEquals(1, t.size());
        assertArrayEquals("b".getBytes(), t.remove(k(1)));
        assertNull(t.get(k(1)));
        assertNull(t.remove(k(1)));
        assertEquals(0, t.size());
    }

    @Test
    void 키는_내용으로_비교한다() {
        t.put("x".getBytes(), "1".getBytes());
        assertArrayEquals("1".getBytes(), t.get(new byte[] {'x'}));
    }

    @Test
    void 키_수가_칸_수에_닿으면_rehash가_시작된다() {
        for (int i = 0; i < HashTable.INITIAL_SIZE - 1; i++) t.put(k(i), k(i));
        assertFalse(t.isRehashing());
        t.put(k(99), k(99));
        assertTrue(t.isRehashing());
        assertEquals(HashTable.INITIAL_SIZE * 3, t.capacity()); // 4 + 8
    }

    @Test
    void rehash_도중_모든_키가_보이고_한_곳에만_있다() {
        int n = 1000;
        for (int i = 0; i < n; i++) {
            t.put(k(i), k(i));
            assertEquals(i + 1, entriesInArrays(), "삽입 " + i + " 뒤 중복 또는 유실");
        }
        for (int i = 0; i < n; i++) assertArrayEquals(k(i), t.get(k(i)));
        assertEquals(n, t.size());
    }

    @Test
    void rehash_도중_삭제는_두_배열_모두에서_찾는다() {
        for (int i = 0; i < 64; i++) t.put(k(i), k(i)); // 64번째에서 rehash 시작
        assertTrue(t.isRehashing());
        for (int i = 0; i < 64; i += 2) assertArrayEquals(k(i), t.remove(k(i)));
        for (int i = 0; i < 64; i++) {
            if (i % 2 == 0) assertNull(t.get(k(i)));
            else assertArrayEquals(k(i), t.get(k(i)));
        }
        assertEquals(32, t.size());
        assertEquals(32, entriesInArrays());
    }

    @Test
    void 요청_없이도_rehash를_끝까지_할_수_있다() {
        for (int i = 0; i < 64; i++) t.put(k(i), k(i));
        while (t.rehash(1)) { }
        assertFalse(t.isRehashing());
        assertEquals(128, t.capacity());
        for (int i = 0; i < 64; i++) assertArrayEquals(k(i), t.get(k(i)));
    }

    @Test
    void 키가_10퍼센트_밑으로_줄면_축소하고_키는_그대로_보인다() {
        for (int i = 0; i < 1000; i++) t.put(k(i), k(i));
        while (t.rehash(100)) { }
        assertEquals(1024, t.capacity());
        assertFalse(t.shrinkIfSparse()); // 1000/1024 → 축소 안 함
        for (int i = 10; i < 1000; i++) t.remove(k(i));
        while (t.rehash(100)) { }
        assertTrue(t.shrinkIfSparse());
        assertEquals(1024 + 32, t.capacity()); // 키 10개 × 2 → 32칸 (4가 아님: 바로 다시 커지지 않게)
        assertEquals(10, entriesInArrays());
        while (t.rehash(1)) { }
        assertEquals(32, t.capacity());
        for (int i = 0; i < 10; i++) assertArrayEquals(k(i), t.get(k(i)));
        assertFalse(t.shrinkIfSparse());
    }

    @Test
    void 축소_도중에도_삽입은_새_배열에만_간다() {
        for (int i = 0; i < 512; i++) t.put(k(i), k(i));
        while (t.rehash(100)) { }
        for (int i = 20; i < 512; i++) t.remove(k(i));
        while (t.rehash(100)) { }
        assertTrue(t.shrinkIfSparse());
        for (int i = 1000; i < 1050; i++) {
            t.put(k(i), k(i)); // 축소 rehash 중 삽입: 새 배열(64칸)이 부하율 1을 넘어도 확장은 rehash가 끝난 뒤
            assertEquals(t.size(), entriesInArrays());
        }
        while (t.rehash(100)) { }
        for (int i = 1000; i < 1050; i++) assertArrayEquals(k(i), t.get(k(i)));
        assertEquals(70, t.size());
    }

    @Test
    void 빈_칸이_많아도_표본은_칸_상한_안에서_끝나고_중복이_없다() {
        for (int i = 0; i < 5000; i++) t.put(k(i), k(i));
        for (int i = 10; i < 5000; i++) t.remove(k(i)); // 축소 없음 → 칸 대부분 빔
        List<HashTable.Entry<byte[]>> out = new ArrayList<>();
        t.sample(20, 200, new SplittableRandom(7), out);
        assertTrue(out.size() <= 10);
        Set<String> seen = new HashSet<>();
        for (var e : out) assertTrue(seen.add(new String(e.key)), "중복 표본");
    }
}
