package demo;

/**
 * 캐시 저장소 (미니 레디스). 실패 · 타임아웃은 RuntimeException으로 던진다 → 호출하는 쪽이 DB로 우회 (Q21).
 * 테스트는 메모리 구현으로 바꿔 끼운다.
 */
public interface Kv {
    String get(String key);

    void setex(String key, int seconds, String value);

    long incr(String key);

    long del(String key);

    long dbsize();

    /** 직접 호출 창용. 허용 목록 검사는 부르는 쪽(Console)이 한다. */
    Object command(String name, String... args);
}
