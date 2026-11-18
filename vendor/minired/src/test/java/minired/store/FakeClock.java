package minired.store;

/** 테스트에서 손으로 넘기는 시계 (Q7). */
public final class FakeClock implements Clock {
    private long millis = 1_700_000_000_000L;
    private long nanos = 0;

    @Override public long nowMillis() { return millis; }
    @Override public long nanoTime() { return nanos; }

    public void advanceMillis(long ms) {
        millis += ms;
        nanos += ms * 1_000_000L;
    }
}
