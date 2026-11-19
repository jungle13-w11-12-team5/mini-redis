# 흐름 지도 — mini-stack 캐시 데모 (경로 E)

> 미니레디스 `docs/flow-map.md` 경로 E "예정"을 옮겨 와 구현 결과로 바꿔 쓴 것. 결정 근거는 `Q2-통합-데모.md`.

## 상자
```
브라우저(mini-react 화면) ─┐
k6(부하, 컨테이너)      ──┼─ HTTP ─▶ demo (Java 21, JDK HttpServer, 가상 스레드)
curl(e2e 테스트)       ──┘            Api ──▶ UserCache ──▶ Kv (JedisKv, 풀 · 50ms · 차단기) ─RESP─▶ minired
                                                    └──▶ Db (HttpDb, 세마포어 ≤ 워커) ─HTTP─▶ minidb
```
- 의존 방향: Api → UserCache → (Kv, Db). UserCache는 HTTP를 모르고, 테스트는 Kv · Db를 메모리 대역으로 바꿔 끼운다
- minired · minidb는 데모를 모른다 (vendor 복사본, 수정 없음)

## 경로 E — 읽기 (`GET /api/users/{id}`, `GET /api/users?name=`)
```
E0. 입구 검사: id = 1 이상 정수 / name = 퍼센트 디코딩 → UTF-8 엄격 → 1..16바이트 → 제어 문자 거부   (Q27)
E1. 캐시 끔?            → E4로 (X-Cache: off, 캐시에 쓰지 않음)
E2. GET users:ver → v   (없으면 0. 실패 · 50ms 초과 · 정수 아님 → E4로, X-Cache: bypass)         (Q13-C, Q21)
E3. GET users:v{v}:{id|name}:{arg}   hit → E6                                                  (Q16)
E4. [세마포어 ≤ DBMS 워커 수] POST /query  SELECT * FROM users WHERE id = N | name = '<'→''>'   (Q20)
    실패 → 503, 캐시에 안 씀 / 200인데 마지막 줄 ok:false → 502, 캐시에 안 씀                  (Q16, Q21)
    [테스트 지점: X-Test-Pause-Ms — DB 응답 뒤 · SET 전 대기]                                   (C2)
E5. 결과 > 256KB → SET 안 함 (X-Cache: skip) / 아니면 SET 키 NDJSON원문 EX 60 (0행이면 EX 5)     (Q17 · Q18 · Q29)
    SET 실패 → 응답은 그대로 (캐시는 가속일 뿐)
E6. 응답: NDJSON 원문 + X-Cache: hit|miss|bypass|skip|off (+ X-Trace 요청 시 단계 JSON)          (Q25)
    id 조회 0행 → 404
```

## 경로 E-쓰기
- API 경유 `PUT /api/users/{id}?age=&name=`: DBMS `UPDATE … WHERE id = N` 성공 → `INCR users:ver` → 응답. 바뀐 행 0이면 무효화 안 함(404)
- DB 직접 `PUT /api/db/users/{id}`: UPDATE만. 레디스는 모름 → TTL(60초)까지 옛 값 (화면에서 보여 주는 용도)
- 순서 고정: DB 성공 → INCR. 반대면 INCR 직후 · DB 반영 전 읽기가 옛 값을 새 버전 키에 넣는다
- INCR 실패(레디스 다운) → DB는 이미 바뀜. 응답 본문 `invalidation`에 실패를 적고 200. TTL까지 옛 값 가능
- 비교용 DEL 모드(`POST /api/settings?mode=del`): 키에 버전 없음, API 쓰기 뒤 `DEL users:id:{id}`. 검색 키는 못 지움

## 경로 E-경합 (C2, 테스트로 고정)
```
읽기 R: GET ver(v1) → GET 키 miss → DB SELECT (age=50) ─── [멈춤] ─────────────── SET users:v1:id:N (50)
쓰기 W:                                          UPDATE age=51 → INCR ver(v2)
다음 읽기:                                                                            GET users:v2:id:N → miss → 51 ✔
DEL 모드면 R이 SET users:id:N (50) → 다음 읽기 hit 50 ✘ (TTL 60초까지)
```
- 단위: `UserCacheTest.raceGenerationModeIsHarmless` · `raceDelModeLeavesStaleValue` (래치로 순서 고정)
- 실제 스택: `demo/test/e2e.sh` "C2" 절 (X-Test-Pause-Ms), 화면: ② 패널 "경합 재현" 버튼

## 장애 경로
| 상황 | 동작 | 확인 |
|---|---|---|
| 레디스 컨테이너 멈춤 | 이름 조회부터 실패 → 첫 요청 ~0.4초, 그 뒤 1초 차단기로 바로 DB (bypass) | e2e |
| 레디스 복구 | JVM DNS 캐시 1초 · 실패 캐시 0초 → 바로 다시 붙음. ver는 비어 0부터 (키도 비어 있어 옛 값 없음) | e2e |
| DB 멈춤 | hit는 계속 응답, miss는 503, 실패는 캐시에 안 씀 | e2e |
| DB 스트리밍 중 실패 | 마지막 줄 ok:false → 502, 캐시에 안 씀 | 단위 |
| ver 값 손상 (`SET users:ver abc`) | bypass | 단위 |

## 시작 · 재시드
- demo 시작 때 `INCR users:ver` 1번 (Q13): 레디스가 ver를 잃거나 되돌아가도 옛 키가 다시 살아나지 않게
- `scripts/reset.sh`: DB 볼륨 삭제 → 시드 10만 행 → 레디스 · 데모 새 컨테이너 (벤치 회차마다, Q28)
