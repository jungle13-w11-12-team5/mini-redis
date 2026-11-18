package demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 미니 DBMS HTTP 클라이언트.
 * - 동시 호출 수 ≤ permits (Q20): DBMS는 keep-alive 연결 하나가 워커 하나를 붙잡는다(DBMS §19).
 *   동시에 쓰는 연결이 permits를 넘지 않으면 클라이언트 풀도 그 이상 연결을 만들지 않는다.
 * - Content-Type: text/plain 필수: cpp-httplib는 form-urlencoded 본문을 8KB로 잘라 413을 낸다.
 * - HTTP/1.1 고정: 기본값(HTTP/2)은 h2c 업그레이드 헤더를 보낸다.
 */
final class HttpDb implements Db {
    private final HttpClient http;
    private final URI queryUri;
    private final Semaphore permits;

    HttpDb(String baseUrl, int maxConcurrency) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.queryUri = URI.create(baseUrl + "/query");
        this.permits = new Semaphore(maxConcurrency, true);
    }

    @Override
    public DbResult query(String sql) throws Unavailable {
        try {
            if (!permits.tryAcquire(3, TimeUnit.SECONDS)) throw new Unavailable("DB 동시 호출 상한 대기 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted");
        }
        try {
            var req = HttpRequest.newBuilder(queryUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new DbResult(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new Unavailable("DB 연결 실패: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted");
        } finally {
            permits.release();
        }
    }
}
