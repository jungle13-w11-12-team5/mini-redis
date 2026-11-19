// API 호출 · 추적 정리. 화면 라이브러리(mini-react)를 모르는 순수 함수 모듈 (Q24 규칙)
// 화면을 바닐라 JS로 바꿔도 이 파일은 그대로 쓴다.

/** NDJSON 원문 → { columns, rows, ok, rowCount, error } */
export function parseNdjson(text) {
  const lines = text.split('\n').filter((l) => l.trim() !== '');
  const out = { columns: [], rows: [], ok: false, rowCount: 0, error: null };
  for (const line of lines) {
    let v;
    try {
      v = JSON.parse(line);
    } catch {
      out.error = '해석 못 한 줄: ' + line.slice(0, 80);
      continue;
    }
    if (Array.isArray(v)) out.rows.push(v);
    else if (v.columns) out.columns = v.columns;
    else if (v.ok === true) { out.ok = true; out.rowCount = v.rowCount ?? out.rows.length; }
    else if (v.ok === false) out.error = typeof v.error === 'string' ? v.error : (v.error?.message ?? 'error');
  }
  return out;
}

/** X-Trace 헤더 → 단계 배열 [{target, cmd, result, us}] */
export function parseTrace(header) {
  if (!header) return [];
  try {
    const steps = JSON.parse(header);
    return Array.isArray(steps) ? steps : [];
  } catch {
    return [];
  }
}

/** 단계 배열 → 대상별 합계 · 호출 수 · 막대 폭(%) */
export function summarizeTrace(steps) {
  const total = steps.reduce((s, x) => s + x.us, 0);
  const by = { redis: { calls: 0, us: 0 }, minidb: { calls: 0, us: 0 }, demo: { calls: 0, us: 0 } };
  for (const s of steps) {
    const t = by[s.target] ?? (by[s.target] = { calls: 0, us: 0 });
    t.calls += 1;
    t.us += s.us;
  }
  const bars = steps.map((s) => ({ ...s, pct: total === 0 ? 0 : Math.max(1.5, (s.us / total) * 100) }));
  return { total, by, bars };
}

export function formatUs(us) {
  if (us >= 1000) return (us / 1000).toFixed(us >= 100000 ? 0 : 1) + 'ms';
  return us + 'µs';
}

/** 요청 하나 → 화면이 쓰는 기록 { label, status, cache, ms, steps, data, json } */
async function call(label, url, init = {}) {
  const headers = { 'X-Trace': '1', ...(init.headers ?? {}) };
  const t0 = performance.now();
  let res;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (e) {
    return { label, status: 0, cache: '-', ms: performance.now() - t0, steps: [], data: null, json: null, error: String(e) };
  }
  const text = await res.text();
  const ms = performance.now() - t0;
  const type = res.headers.get('Content-Type') ?? '';
  const rec = {
    label,
    status: res.status,
    cache: res.headers.get('X-Cache') ?? '-',
    ms,
    steps: parseTrace(res.headers.get('X-Trace')),
    data: null,
    json: null,
    error: null,
  };
  if (type.includes('ndjson')) rec.data = parseNdjson(text);
  else {
    try {
      rec.json = JSON.parse(text);
      if (rec.json.ok === false) rec.error = typeof rec.json.error === 'string' ? rec.json.error : JSON.stringify(rec.json.error);
    } catch {
      rec.error = text.slice(0, 200);
    }
  }
  return rec;
}

export const getById = (id) => call(`GET /api/users/${id}`, `/api/users/${encodeURIComponent(id)}`);
export const searchByName = (name) => call(`GET /api/users?name=${name}`, `/api/users?name=${encodeURIComponent(name)}`);

/** via = 'api' (DB 쓰기 → 무효화) | 'direct' (DB만) */
export function writeAge(id, age, via) {
  const path = via === 'api' ? `/api/users/${encodeURIComponent(id)}` : `/api/db/users/${encodeURIComponent(id)}`;
  return call(`PUT ${path}?age=${age}`, `${path}?age=${encodeURIComponent(age)}`, { method: 'PUT' });
}

export async function getSettings() {
  return (await fetch('/api/settings')).json();
}

export async function setSettings(patch) {
  const q = new URLSearchParams(patch).toString();
  return (await fetch('/api/settings?' + q, { method: 'POST' })).json();
}

export async function getStats() {
  return (await fetch('/api/stats')).json();
}

export async function resetStats() {
  return (await fetch('/api/stats/reset', { method: 'POST' })).json();
}

/** 직접 호출 창: kind = 'redis' | 'db' */
export async function consoleCall(kind, text) {
  const t0 = performance.now();
  const res = await fetch(`/api/console/${kind}`, { method: 'POST', body: text });
  const ms = performance.now() - t0;
  if (res.status !== 200) return { kind, input: text, ok: false, output: `HTTP ${res.status}`, us: 0, ms };
  const j = await res.json();
  const output = kind === 'redis' ? j.reply : `HTTP ${j.status}\n${j.body}`;
  return { kind, input: text, ok: j.ok, output, us: j.us, ms };
}

/**
 * 경합 재현 (C2): 읽기를 DB 응답 뒤 · SET 전에 pauseMs 세워 두고, 그 사이 API 쓰기를 끝낸 뒤 다시 읽는다.
 * 서버가 DEMO_TEST_HOOKS=on이어야 한다. 돌려주는 것: 단계별 기록 4개
 */
export async function runRace(id, oldAge, newAge, pauseMs = 1200) {
  await call('준비', `/api/users/${id}?age=${oldAge}`, { method: 'PUT' });
  const slow = call(`① 느린 읽기 (DB 뒤 ${pauseMs}ms 멈춤)`, `/api/users/${id}`, { headers: { 'X-Test-Pause-Ms': String(pauseMs) } });
  await new Promise((r) => setTimeout(r, pauseMs / 3));
  const write = await writeAge(id, newAge, 'api');
  write.label = `② 그 사이 API 쓰기 age=${newAge}`;
  const read1 = await slow;
  const after = await getById(id);
  after.label = '③ 다음 읽기';
  const age = after.data?.rows?.[0]?.[2];
  return { steps: [read1, write, after], stale: age === oldAge, age };
}

/** 마지막 읽기 결과 → 캐시 키 (직접 호출 창 바로가기용) */
export function cacheKeyOf(rec) {
  const set = rec?.steps?.find((s) => s.target === 'redis' && /^(GET|SET) users:/.test(s.cmd) && !s.cmd.includes('users:ver'));
  return set ? set.cmd.split(' ')[1] : null;
}
