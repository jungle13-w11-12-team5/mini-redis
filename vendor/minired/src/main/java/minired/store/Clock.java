package minired.store;

/**
 * 서버가 시각을 읽는 유일한 통로 (Q7, B3).
 * 벽시계는 만료 절대 시각 · TTL에, 단조 시계는 타이머 · 시간 상한에 쓴다.
 */
public interface Clock {
    /** 벽시계(epoch ms). 사람이 시계를 바꾸면 같이 바뀐다. */
    long nowMillis();

    /** 단조 시계(ns). 경과 시간 측정에만 쓴다. */
    long nanoTime();

    static Clock system() {
        return new Clock() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
            @Override public long nanoTime() { return System.nanoTime(); }
        };
    }
}
