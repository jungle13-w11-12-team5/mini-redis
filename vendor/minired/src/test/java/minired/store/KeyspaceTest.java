package minired.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class KeyspaceTest {
    private final FakeClock clock = new FakeClock();
    private final Keyspace ks = new Keyspace(clock, new SipHash(3, 4), new SplittableRandom(1));

    private static byte[] b(String s) { return s.getBytes(); }

    @Test
    void 저장_조회_삭제() {
        ks.set(b("k"), b("v"));
        assertArrayEquals(b("v"), ks.get(b("k")));
        assertTrue(ks.exists(b("k")));
        assertTrue(ks.delete(b("k")));
        assertFalse(ks.delete(b("k")));
        assertNull(ks.get(b("k")));
    }

    @Test
    void 기간이_지나면_조회에서_없고_두_테이블에서_지워진다() {
        ks.setWithTtl(b("k"), b("v"), 1000);
        clock.advanceMillis(999);
        assertArrayEquals(b("v"), ks.get(b("k")));
        clock.advanceMillis(1);
        assertNull(ks.get(b("k")));
        assertEquals(0, ks.size());
        assertEquals(0, ks.expiresSize());
    }

    @Test
    void TTL_응답값() {
        assertEquals(-2, ks.ttlMillis(b("none")));
        ks.set(b("p"), b("v"));
        assertEquals(-1, ks.ttlMillis(b("p")));
        ks.setWithTtl(b("t"), b("v"), 5000);
        clock.advanceMillis(1200);
        assertEquals(3800, ks.ttlMillis(b("t")));
    }

    @Test
    void 기간_있는_키에_SET을_다시_하면_기간이_사라진다() {
        ks.setWithTtl(b("k"), b("v"), 1000);
        ks.set(b("k"), b("v2"));
        clock.advanceMillis(5000);
        assertArrayEquals(b("v2"), ks.get(b("k")));
        assertEquals(0, ks.expiresSize());
    }

    @Test
    void replaceValue는_기간을_유지한다() {
        ks.setWithTtl(b("k"), b("1"), 1000);
        ks.replaceValue(b("k"), b("2"));
        assertEquals(1000, ks.ttlMillis(b("k")));
        clock.advanceMillis(1000);
        assertNull(ks.get(b("k")));
    }

    @Test
    void expire_0이하면_바로_지우고_없는_키는_false() {
        assertFalse(ks.expire(b("none"), 1000));
        ks.set(b("k"), b("v"));
        assertTrue(ks.expire(b("k"), 0));
        assertFalse(ks.exists(b("k")));
    }

    @Test
    void 만료된_키는_다시_기간을_줄_수_없다() {
        ks.setWithTtl(b("k"), b("v"), 10);
        clock.advanceMillis(10);
        assertFalse(ks.expire(b("k"), 1000));
    }

    @Test
    void 아무도_안_읽는_만료_키는_순찰이_회수한다() {
        for (int i = 0; i < 500; i++) ks.setWithTtl(b("e" + i), b("v"), 100);
        for (int i = 0; i < 50; i++) ks.set(b("p" + i), b("v"));
        clock.advanceMillis(100);
        for (int i = 0; i < 100 && ks.expiresSize() > 0; i++) ks.cron();
        assertEquals(0, ks.expiresSize());
        assertEquals(50, ks.size());
    }

    @Test
    void 순찰은_지난_비율이_낮으면_한_번만_돈다() {
        for (int i = 0; i < 1000; i++) ks.setWithTtl(b("k" + i), b("v"), 100_000);
        ks.setWithTtl(b("short"), b("v"), 1);
        clock.advanceMillis(1);
        ks.activeExpireCycle(); // 20개 중 많아야 1개 지남 → 반복 안 함
        assertTrue(ks.expiresSize() >= 1000);
    }

    @Test
    void 만료로_대부분_지워지면_cron이_테이블을_줄인다() {
        for (int i = 0; i < 2000; i++) ks.setWithTtl(b("e" + i), b("v"), 100);
        for (int i = 0; i < 5; i++) ks.set(b("p" + i), b("v"));
        for (int i = 0; i < 100 && ks.isRehashing(); i++) ks.cron();
        assertEquals(2048, ks.capacity());
        clock.advanceMillis(100);
        for (int i = 0; i < 200 && (ks.expiresSize() > 0 || ks.isRehashing() || ks.capacity() > 16); i++) ks.cron();
        assertEquals(5, ks.size());
        assertEquals(16, ks.capacity()); // 키 5개 × 2 → 16칸
    }

    @Test
    void 요청이_없어도_cron이_rehash를_끝낸다() {
        for (int i = 0; i < 1024; i++) ks.set(b("k" + i), b("v"));
        assertTrue(ks.isRehashing());
        for (int i = 0; i < 100 && ks.isRehashing(); i++) ks.cron();
        assertFalse(ks.isRehashing());
    }
}
