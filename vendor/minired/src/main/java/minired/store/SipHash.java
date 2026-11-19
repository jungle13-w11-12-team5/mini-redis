package minired.store;

/**
 * SipHash-2-4 (B11). 128비트 비밀 키를 모르면 충돌을 미리 만들 수 없다.
 * 다항식 해시(Arrays.hashCode)는 시드를 섞어도 같은 길이 충돌이 남아 쓰지 않는다.
 */
final class SipHash {
    private final long k0;
    private final long k1;

    SipHash(long k0, long k1) {
        this.k0 = k0;
        this.k1 = k1;
    }

    long hash(byte[] data) {
        long[] v = {
            0x736f6d6570736575L ^ k0,
            0x646f72616e646f6dL ^ k1,
            0x6c7967656e657261L ^ k0,
            0x7465646279746573L ^ k1,
        };
        int len = data.length;
        int end = len - (len % 8);
        for (int i = 0; i < end; i += 8) {
            compress(v, readLongLE(data, i));
        }
        long last = ((long) len) << 56;
        for (int j = end; j < len; j++) {
            last |= (data[j] & 0xffL) << (8 * (j - end));
        }
        compress(v, last);

        v[2] ^= 0xff;
        for (int r = 0; r < 4; r++) {
            round(v);
        }
        return v[0] ^ v[1] ^ v[2] ^ v[3];
    }

    /** 메시지 블록 8바이트 하나를 2라운드로 섞는다 (2-4의 "2"). */
    private static void compress(long[] v, long m) {
        v[3] ^= m;
        round(v);
        round(v);
        v[0] ^= m;
    }

    private static void round(long[] v) {
        v[0] += v[1]; v[1] = Long.rotateLeft(v[1], 13); v[1] ^= v[0]; v[0] = Long.rotateLeft(v[0], 32);
        v[2] += v[3]; v[3] = Long.rotateLeft(v[3], 16); v[3] ^= v[2];
        v[0] += v[3]; v[3] = Long.rotateLeft(v[3], 21); v[3] ^= v[0];
        v[2] += v[1]; v[1] = Long.rotateLeft(v[1], 17); v[1] ^= v[2]; v[2] = Long.rotateLeft(v[2], 32);
    }

    private static long readLongLE(byte[] d, int i) {
        long m = 0;
        for (int k = 7; k >= 0; k--) {
            m = (m << 8) | (d[i + k] & 0xffL);
        }
        return m;
    }
}
