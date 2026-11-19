package minired.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SipHashTest {
    // 논문(Aumasson & Bernstein, 2012) 기준 키: 00 01 .. 0f
    private final SipHash reference = new SipHash(0x0706050403020100L, 0x0f0e0d0c0b0a0908L);

    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }

    @Test
    void 논문_예시_15바이트() {
        assertEquals(0xa129ca6149be45e5L, reference.hash(seq(15)));
    }

    @Test
    void 참조_구현_벡터_빈_입력() {
        assertEquals(0x726fdb47dd0e0e31L, reference.hash(seq(0)));
    }

    @Test
    void 다항식_해시가_충돌하는_쌍도_키가_있으면_갈린다() {
        // "Aa".hashCode() == "BB".hashCode()
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(reference.hash("Aa".getBytes()), reference.hash("BB".getBytes()));
    }
}
