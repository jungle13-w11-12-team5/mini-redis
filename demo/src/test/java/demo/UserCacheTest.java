package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import demo.Fakes.FakeDb;
import demo.Fakes.FakeKv;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserCacheTest {
    FakeKv kv;
    FakeDb db;
    UserCache c;

    @BeforeEach
    void setUp() {
        kv = new FakeKv();
        db = new FakeDb();
        db.rows.put(1L, new FakeDb.User("kim", 20));
        db.rows.put(2L, new FakeDb.User("kim", 30));
        db.rows.put(3L, new FakeDb.User("o'neil", 40));
        c = new UserCache(kv, db, 60, 5, 256 * 1024);
        c.bumpOnStart();
    }

    UserCache.Result id(long id) {
        return c.byId(id, Trace.off(), UserCache.Hook.NONE);
    }

    @Test
    void missThenHit() {
        assertEquals("miss", id(1).cache());
        var r = id(1);
        assertEquals("hit", r.cache());
        assertTrue(r.body().contains("[1,\"kim\",20]"));
        assertEquals(1, db.selects);
        assertEquals(60, kv.ttl.get("users:v1:id:1"));
    }

    @Test
    void startBumpsVersion() {
        assertEquals("1", kv.data.get("users:ver"));
        c.bumpOnStart();
        assertEquals("2", kv.data.get("users:ver"));
    }

    @Test
    void emptyResultCachedShort() {
        var r = id(99);
        assertEquals(404, r.status());
        assertEquals(5, kv.ttl.get("users:v1:id:99"));
        assertEquals("hit", id(99).cache());
        assertEquals(404, id(99).status());
    }

    @Test
    void apiWriteBumpsVersionAndInvalidatesSearchToo() {
        id(1);
        c.byName("kim", Trace.off(), UserCache.Hook.NONE);
        var w = c.update(1, null, 21L, true, Trace.off());
        assertEquals(200, w.status());
        assertEquals("2", kv.data.get("users:ver"));
        var r = id(1);
        assertEquals("miss", r.cache());
        assertTrue(r.body().contains("[1,\"kim\",21]"));
        var s = c.byName("kim", Trace.off(), UserCache.Hook.NONE);
        assertEquals("miss", s.cache());   // 검색 키도 한 번에 낡음
        assertTrue(s.body().contains("[1,\"kim\",21]"));
    }

    @Test
    void directWriteLeavesStaleUntilTtl() {
        id(1);
        c.update(1, null, 21L, false, Trace.off());
        var r = id(1);
        assertEquals("hit", r.cache());
        assertTrue(r.body().contains("[1,\"kim\",20]"), "DB 직접 쓰기는 캐시가 모름");
    }

    @Test
    void delModeLeavesSearchStale() {
        c.mode = UserCache.Mode.DEL;
        id(1);
        c.byName("kim", Trace.off(), UserCache.Hook.NONE);
        c.update(1, null, 21L, true, Trace.off());
        assertEquals("miss", id(1).cache());
        var s = c.byName("kim", Trace.off(), UserCache.Hook.NONE);
        assertEquals("hit", s.cache());
        assertTrue(s.body().contains("[1,\"kim\",20]"), "DEL 방식은 검색 결과 키를 못 지움");
    }

    @Test
    void redisDownBypassesToDb() {
        kv.down = true;
        var r = id(1);
        assertEquals("bypass", r.cache());
        assertEquals(200, r.status());
        // 쓰기: DB는 성공, 무효화는 실패로 보고
        var w = c.update(1, null, 22L, true, Trace.off());
        assertEquals(200, w.status());
        assertTrue(w.body().contains("실패"), w.body());
    }

    @Test
    void corruptVersionBypasses() {
        kv.data.put("users:ver", "abc");
        assertEquals("bypass", id(1).cache());
    }

    @Test
    void dbDownIsNotCached() {
        db.down = true;
        var r = id(1);
        assertEquals(503, r.status());
        assertEquals(1, kv.data.size());   // users:ver만
    }

    @Test
    void streamFailureLastLineNotCached() {
        db.forcedBody = "{\"columns\":[\"id\",\"name\",\"age\"]}\n[1,\"kim\",20]\n{\"ok\":false,\"error\":{\"type\":\"StorageError\",\"message\":\"x\"}}\n";
        var r = id(1);
        assertEquals(502, r.status());
        assertNull(kv.data.get("users:v1:id:1"));
    }

    @Test
    void bigResultSkipped() {
        c = new UserCache(kv, db, 60, 5, 64);
        var r = c.byName("kim", Trace.off(), UserCache.Hook.NONE);
        assertEquals("skip", r.cache());
        assertEquals(200, r.status());
        assertNull(kv.data.get("users:v1:name:kim"));
    }

    @Test
    void cacheOffGoesToDb() {
        c.cacheOn = false;
        assertEquals("off", id(1).cache());
        assertEquals("off", id(1).cache());
        assertEquals(2, db.selects);
    }

    @Test
    void quoteInName() {
        var r = c.byName("o'neil", Trace.off(), UserCache.Hook.NONE);
        assertTrue(r.body().contains("[3,"));
    }

    @Test
    void traceRecordsSteps() {
        Trace t = Trace.on();
        c.byId(1, t, UserCache.Hook.NONE);
        String j = t.toJson();
        assertTrue(j.contains("GET users:ver"), j);
        assertTrue(j.contains("POST /query"), j);
        assertTrue(j.contains("SET users:v1:id:1"), j);
    }

    // ---- C2: 무효화 경합 재현. 읽기가 DB에서 옛 값을 받은 뒤 · SET 전에 쓰기(UPDATE + 무효화)가 끝난다

    /** 읽기 하나를 DB 읽은 직후에 세워 두고, 그 사이 API 쓰기를 끝낸 뒤 읽기를 풀어 준다 */
    private void raceReadAgainstWrite() throws Exception {
        var dbRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        UserCache.Hook hook = () -> {
            dbRead.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var reader = CompletableFuture.supplyAsync(() -> c.byId(1, Trace.off(), hook));
        assertTrue(dbRead.await(5, TimeUnit.SECONDS));
        assertEquals(200, c.update(1, null, 99L, true, Trace.off()).status());
        release.countDown();
        var r = reader.get(5, TimeUnit.SECONDS);
        assertTrue(r.body().contains("[1,\"kim\",20]"), "늦은 읽기는 옛 값을 받는다 (둘 다 같음)");
    }

    @Test
    void raceDelModeLeavesStaleValue() throws Exception {
        c.mode = UserCache.Mode.DEL;
        raceReadAgainstWrite();
        var after = id(1);
        assertEquals("hit", after.cache());
        assertTrue(after.body().contains("[1,\"kim\",20]"), "DEL 방식: 늦게 온 SET이 옛 값을 TTL(60초)까지 남김");
    }

    @Test
    void raceGenerationModeIsHarmless() throws Exception {
        raceReadAgainstWrite();
        var after = id(1);
        assertEquals("miss", after.cache());
        assertTrue(after.body().contains("[1,\"kim\",99]"), "세대 번호: 옛 값은 아무도 안 읽는 v1 키에 써짐");
        assertTrue(kv.data.get("users:v1:id:1").contains("[1,\"kim\",20]"));
        assertFalse(kv.data.get("users:v2:id:1").contains("20]"));
    }
}
