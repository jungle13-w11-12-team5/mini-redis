// k6 시나리오 (Q15): 같은 API를 캐시 켬 / 끔으로. 조회 키는 Zipf(소수 키에 몰림), 쓰기 비율 WRITE_PCT.
// 실행: bench/run.sh (회차마다 DB 재시드 · 레디스 비움 · 설정 후 이걸 돌린다)
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://demo:8080';
const WRITE_PCT = Number(__ENV.WRITE_PCT || 0);      // 0 · 1 · 10
const NAME_PCT = Number(__ENV.NAME_PCT || 20);       // 읽기 중 이름 검색(전체 스캔) 비율
const ROWS = Number(__ENV.ROWS || 100000);
const ZIPF_S = Number(__ENV.ZIPF_S || 1.0);
const RUN = __ENV.RUN || 'run';

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: Number(__ENV.VUS || 32), duration: __ENV.DURATION || '30s' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

// Zipf 표본: 누적 분포를 한 번 만들고 이분 탐색. 순위 1이 가장 자주 나온다
function zipfCdf(n, s) {
  const cdf = new Float64Array(n);
  let sum = 0;
  for (let k = 1; k <= n; k++) { sum += 1 / Math.pow(k, s); cdf[k - 1] = sum; }
  for (let i = 0; i < n; i++) cdf[i] /= sum;
  return cdf;
}
function sample(cdf) {
  const u = Math.random();
  let lo = 0, hi = cdf.length - 1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
  return lo;   // 0-based 순위
}

// 순위 → id: 순위 그대로 쓰면 hot 키가 id 1, 2, 3...이 된다. 섞어서 테이블 전체에 흩는다 (곱셈 해시, ROWS와 서로소)
const ID_CDF = zipfCdf(ROWS, ZIPF_S);
const rankToId = (r) => ((r * 7919) % ROWS) + 1;

// 이름: kim(2만 행, 캐시 상한 넘음)은 뺀다 — 캐시와 무관하게 응답 크기만 재게 됨
const NAMES = ['lee', 'park', '김민수'];
for (let i = 0; i < 10000; i++) NAMES.push('user' + String((i * 7919) % 10000).padStart(5, '0'));
const NAME_CDF = zipfCdf(NAMES.length, ZIPF_S);

const lat = { id: new Trend('lat_id', true), name: new Trend('lat_name', true), write: new Trend('lat_write', true) };
const cache = {};
for (const c of ['hit', 'miss', 'bypass', 'skip', 'off']) cache[c] = new Counter('cache_' + c);
const errors = new Counter('errors');

export default function () {
  if (Math.random() * 100 < WRITE_PCT) {
    const id = rankToId(sample(ID_CDF));
    const r = http.put(`${BASE}/api/users/${id}?age=${18 + Math.floor(Math.random() * 60)}`, null, { tags: { kind: 'write' } });
    lat.write.add(r.timings.duration);
    if (r.status !== 200) errors.add(1);
    return;
  }
  let r, kind;
  if (Math.random() * 100 < NAME_PCT) {
    kind = 'name';
    r = http.get(`${BASE}/api/users?name=${encodeURIComponent(NAMES[sample(NAME_CDF)])}`, { tags: { kind } });
  } else {
    kind = 'id';
    r = http.get(`${BASE}/api/users/${rankToId(sample(ID_CDF))}`, { tags: { kind } });
  }
  lat[kind].add(r.timings.duration);
  if (r.status !== 200 && r.status !== 404) errors.add(1);
  const c = r.headers['X-Cache'];
  if (cache[c]) cache[c].add(1);
}

function pick(data, name, stat) {
  const m = data.metrics[name];
  if (!m) return null;
  return m.values[stat] ?? null;
}

export function handleSummary(data) {
  const out = {
    run: RUN, writePct: WRITE_PCT, namePct: NAME_PCT, zipfS: ZIPF_S, vus: Number(__ENV.VUS || 32),
    reqs: pick(data, 'http_reqs', 'count'),
    rps: pick(data, 'http_reqs', 'rate'),
    errors: pick(data, 'errors', 'count') || 0,
    all: { p50: pick(data, 'http_req_duration', 'p(50)'), p99: pick(data, 'http_req_duration', 'p(99)') },
    id: { p50: pick(data, 'lat_id', 'p(50)'), p99: pick(data, 'lat_id', 'p(99)'), n: pick(data, 'lat_id', 'count') },
    name: { p50: pick(data, 'lat_name', 'p(50)'), p99: pick(data, 'lat_name', 'p(99)'), n: pick(data, 'lat_name', 'count') },
    write: { p50: pick(data, 'lat_write', 'p(50)'), p99: pick(data, 'lat_write', 'p(99)'), n: pick(data, 'lat_write', 'count') },
    cache: Object.fromEntries(Object.keys(cache).map((c) => [c, pick(data, 'cache_' + c, 'count') || 0])),
  };
  return {
    [`/bench/results/raw/${RUN}.json`]: JSON.stringify(out, null, 2),
    stdout: `${RUN}: ${Math.round(out.rps)} req/s · p50 ${out.all.p50?.toFixed(2)}ms · p99 ${out.all.p99?.toFixed(2)}ms · errors ${out.errors}\n`,
  };
}
