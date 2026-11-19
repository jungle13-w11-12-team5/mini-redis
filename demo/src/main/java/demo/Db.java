package demo;

/** 원본 저장소 (미니 DBMS). 연결 실패 · 시간 초과 · 동시 호출 상한 대기 초과는 Unavailable. */
public interface Db {
    DbResult query(String sql) throws Unavailable;

    final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }
}
