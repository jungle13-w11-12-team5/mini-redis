// mini-stack 데모 화면. 직접 만든 mini-react(엔진 + 런타임, vendor 복사본)로 그린다.
// 규칙: 훅은 루트(App)만 · 자식은 props만 받는 함수 · 목록은 key 필수 · effect는 의존 배열 필수 · class/style은 문자열
import { h, useState, useEffect, useMemo, FunctionComponent } from '/vendor/mini-react/index.js';
import * as api from './api.js';

const HISTORY = 8;

// ---------- 자식: 상태 없는 함수

function Badge({ cache }) {
  return h('span', { class: `badge c-${cache}` }, cache);
}

function TopBar({ settings, stats, onCache, onMode, onResetStats }) {
  const total = stats ? stats.hit + stats.miss + stats.bypass + stats.skip : 0;
  const ratio = total === 0 ? '-' : ((stats.hit / total) * 100).toFixed(1) + '%';
  return h('header', { class: 'top' },
    h('div', { class: 'brand' }, h('b', null, 'mini-stack'), h('span', { class: 'sub' }, 'mini-react 화면 · Java 데모 서버 · 미니 레디스 · 미니 DBMS')),
    h('div', { class: 'switches' },
      h('label', { class: 'switch' }, '캐시',
        h('button', { type: 'button', class: settings.cache === 'on' ? 'on' : 'off', onClick: onCache }, settings.cache === 'on' ? '켬' : '끔')),
      h('label', { class: 'switch' }, '무효화',
        h('button', { type: 'button', class: settings.mode === 'gen' ? 'on' : 'warn', onClick: onMode },
          settings.mode === 'gen' ? '세대 번호 (C)' : 'DEL (B, 비교용)'))),
    h('div', { class: 'stats' },
      stats === null ? '통계 없음' : h('span', null,
        `hit ${stats.hit} · miss ${stats.miss} · bypass ${stats.bypass} · skip ${stats.skip} · off ${stats.off}`,
        h('b', null, `  hit 비율 ${ratio}`),
        `  · users:ver ${stats.ver ?? '(nil)'} · DBSIZE ${stats.dbsize ?? '레디스 끊김'}`),
      h('button', { type: 'button', class: 'ghost', onClick: onResetStats }, '통계 0으로')));
}

function ResultTable({ rec }) {
  if (rec === null) return h('p', { class: 'muted' }, '조회하면 결과가 여기에 나온다.');
  if (rec.error) return h('p', { class: 'err' }, `HTTP ${rec.status} · ${rec.error}`);
  if (rec.data === null) return h('pre', null, JSON.stringify(rec.json, null, 2));
  const d = rec.data;
  const shown = d.rows.slice(0, 20);
  return h('div', null,
    h('p', { class: 'muted' }, `HTTP ${rec.status} · ${d.rowCount}행${d.rows.length > shown.length ? ` (앞 ${shown.length}행만 표시)` : ''}`),
    h('table', null,
      h('thead', null, h('tr', null, ...d.columns.map((c) => h('th', { key: c }, c)))),
      h('tbody', null, ...shown.map((r) => h('tr', { key: String(r[0]) }, ...r.map((v, i) => h('td', { key: String(i) }, String(v))))))));
}

function LookupPanel({ idText, nameText, last, busy, onId, onName, onGetId, onSearch }) {
  return h('section', { class: 'panel' },
    h('h2', null, '① 조회 (cache-aside)'),
    h('div', { class: 'row' },
      h('input', { value: idText, placeholder: 'id (1~100000)', onInput: (e) => onId(e.target.value) }),
      h('button', { type: 'button', disabled: busy, onClick: onGetId }, 'id 조회')),
    h('div', { class: 'row' },
      h('input', { value: nameText, placeholder: '이름 (kim · lee · 김민수 · user00042)', onInput: (e) => onName(e.target.value) }),
      h('button', { type: 'button', disabled: busy, onClick: onSearch }, '이름 검색 (전체 스캔)')),
    h('p', { class: 'hint' }, 'kim은 결과가 256KB를 넘어 캐시하지 않는다 (skip). 같은 걸 두 번 누르면 miss → hit.'),
    last === null ? null : h('p', null, h(Badge, { cache: last.cache }), ` ${last.label} · ${last.ms.toFixed(1)}ms (브라우저 기준)`),
    h(ResultTable, { rec: last }));
}

function WritePanel({ writeId, writeAge, lastWrite, race, busy, mode, onWriteId, onWriteAge, onWrite, onRace }) {
  return h('section', { class: 'panel' },
    h('h2', null, '② 쓰기 두 길'),
    h('div', { class: 'row' },
      h('input', { value: writeId, placeholder: 'id', onInput: (e) => onWriteId(e.target.value) }),
      h('input', { value: writeAge, placeholder: 'age', onInput: (e) => onWriteAge(e.target.value) })),
    h('div', { class: 'row' },
      h('button', { type: 'button', disabled: busy, onClick: () => onWrite('api') }, 'API 경유 (UPDATE → 무효화)'),
      h('button', { type: 'button', class: 'warn', disabled: busy, onClick: () => onWrite('direct') }, 'DB 직접 (캐시는 모름)')),
    lastWrite === null ? null : h('p', { class: lastWrite.error ? 'err' : '' },
      lastWrite.error ? `HTTP ${lastWrite.status} · ${lastWrite.error}`
        : `${lastWrite.label} → 바뀐 행 ${lastWrite.json.affected} · 무효화: ${lastWrite.json.invalidation}`),
    h('p', { class: 'hint' }, 'DB 직접 쓰기 뒤 같은 id를 조회하면 hit로 옛 값이 나온다 → TTL(60초)이 마지막 안전망.'),
    h('h3', null, `경합 재현 — 지금 모드: ${mode === 'gen' ? '세대 번호' : 'DEL'}`),
    h('p', { class: 'hint' }, '느린 읽기가 DB에서 옛 값을 받은 뒤 · SET 전에 쓰기가 끝나는 순서를 일부러 만든다.'),
    h('button', { type: 'button', disabled: busy, onClick: onRace }, `경합 재현 (id ${writeId || '?'})`),
    race === null ? null : h('div', { class: 'race' },
      ...race.steps.map((s, i) => h('div', { key: String(i), class: 'race-step' },
        h(Badge, { cache: s.cache }), ` ${s.label}`,
        s.data && s.data.rows.length > 0 ? ` → age ${s.data.rows[0][2]}` : '',
        s.json && s.json.invalidation ? ` → ${s.json.invalidation}` : '')),
      h('p', { class: race.stale ? 'err' : 'good' },
        race.stale ? `옛 값(${race.age})이 캐시에 남음 — DEL 방식의 경합 창, TTL까지 유지` : `새 값(${race.age}) — 늦게 온 옛 값은 아무도 안 읽는 옛 버전 키에 써짐`)));
}

function TracePanel({ history, selected, summary, onSelect }) {
  const rec = history[selected] ?? null;
  return h('section', { class: 'panel wide' },
    h('h2', null, '③ 추적 타임라인 (X-Trace)'),
    history.length === 0 ? h('p', { class: 'muted' }, '요청을 보내면 서버가 레디스 · DB를 어떤 순서로 불렀는지 나온다.')
      : h('div', { class: 'trace' },
        h('ul', { class: 'hist' }, ...history.map((r, i) => h('li', { key: r.id, class: i === selected ? 'sel' : '', onClick: () => onSelect(i) },
          h(Badge, { cache: r.cache }), ` ${r.label}`, h('span', { class: 'muted' }, ` ${r.ms.toFixed(1)}ms`)))),
        rec === null ? null : h('div', { class: 'steps' },
          h('p', { class: 'muted' },
            `서버 안 합계 ${api.formatUs(summary.total)} · 레디스 ${summary.by.redis.calls}번 ${api.formatUs(summary.by.redis.us)} · DB ${summary.by.minidb.calls}번 ${api.formatUs(summary.by.minidb.us)}`),
          ...summary.bars.map((s, i) => h('div', { key: String(i), class: 'step' },
            h('div', { class: `who t-${s.target}` }, s.target),
            h('div', { class: 'what' }, h('code', null, s.cmd), h('div', { class: 'res' }, s.result)),
            h('div', { class: 'bar' }, h('div', { class: `fill t-${s.target}`, style: `width:${s.pct.toFixed(1)}%` }), h('span', null, api.formatUs(s.us))))))));
}

function ConsolePanel({ kind, text, log, busy, keyHint, onKind, onText, onRun, onQuick }) {
  const quick = kind === 'redis'
    ? ['GET users:ver', 'DBSIZE', ...(keyHint ? [`TTL ${keyHint}`, `GET ${keyHint}`, `DEL ${keyHint}`] : [])]
    : ['SELECT * FROM users WHERE id = 1', "SELECT * FROM users WHERE name = 'lee'", 'UPDATE users SET age = 99 WHERE id = 1'];
  return h('section', { class: 'panel wide' },
    h('h2', null, '④ 직접 호출 창 — 캐시 로직을 거치지 않음'),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: kind === 'redis' ? 'on' : 'ghost', onClick: () => onKind('redis') }, '미니 레디스'),
      h('button', { type: 'button', class: kind === 'db' ? 'on' : 'ghost', onClick: () => onKind('db') }, '미니 DBMS'),
      h('input', { class: 'grow', value: text, placeholder: kind === 'redis' ? 'GET · SET · DEL · TTL · EXISTS · DBSIZE' : 'SQL',
        onInput: (e) => onText(e.target.value), onKeydown: (e) => { if (e.key === 'Enter') onRun(); } }),
      h('button', { type: 'button', disabled: busy, onClick: onRun }, '실행')),
    h('div', { class: 'row quick' }, ...quick.map((q) => h('button', { key: q, type: 'button', class: 'ghost small', onClick: () => onQuick(q) }, q))),
    h('div', { class: 'log' }, ...log.map((l) => h('div', { key: l.id, class: 'entry' },
      h('code', { class: l.ok ? '' : 'err' }, `${l.kind === 'redis' ? 'redis>' : 'minidb>'} ${l.input}`),
      h('pre', null, l.output),
      h('span', { class: 'muted' }, `서버 안 ${api.formatUs(l.us)} · 왕복 ${l.ms.toFixed(1)}ms`)))));
}

// ---------- 루트: 상태 · effect 전부 여기

function App() {
  const [settings, setSettings] = useState({ cache: 'on', mode: 'gen' });
  const [stats, setStats] = useState(null);
  const [idText, setIdText] = useState('777');
  const [nameText, setNameText] = useState('lee');
  const [last, setLast] = useState(null);
  const [history, setHistory] = useState([]);
  const [selected, setSelected] = useState(0);
  const [writeId, setWriteId] = useState('777');
  const [writeAgeText, setWriteAgeText] = useState('30');
  const [lastWrite, setLastWrite] = useState(null);
  const [race, setRace] = useState(null);
  const [kind, setKind] = useState('redis');
  const [consoleText, setConsoleText] = useState('GET users:ver');
  const [log, setLog] = useState([]);
  const [busy, setBusy] = useState(false);
  const [seq, setSeq] = useState(0);   // 요청이 끝날 때마다 +1 → 통계 다시 읽기

  useEffect(() => {
    api.getSettings().then(setSettings).catch(() => {});
  }, []);

  useEffect(() => {
    let alive = true;
    api.getStats().then((s) => { if (alive) setStats(s); }).catch(() => {});
    return () => { alive = false; };
  }, [seq]);

  const summary = useMemo(() => api.summarizeTrace(history[selected]?.steps ?? []), [history, selected]);

  const record = (rec) => {
    const withId = { ...rec, id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}` };
    setHistory((hs) => [withId, ...hs].slice(0, HISTORY));
    setSelected(0);
    return withId;
  };

  const run = async (task) => {
    setBusy(true);
    try {
      await task();
    } finally {
      setBusy(false);
      setSeq((n) => n + 1);
    }
  };

  const onGetId = () => run(async () => setLast(record(await api.getById(idText.trim()))));
  const onSearch = () => run(async () => setLast(record(await api.searchByName(nameText))));
  const onWrite = (via) => run(async () => {
    const rec = record(await api.writeAge(writeId.trim(), writeAgeText.trim(), via));
    setLastWrite(rec);
  });
  const onRace = () => run(async () => {
    const age = Number(writeAgeText) || 30;
    const r = await api.runRace(writeId.trim(), age, age + 1);
    r.steps.forEach(record);
    setRace(r);
  });
  const onCache = () => api.setSettings({ cache: settings.cache === 'on' ? 'off' : 'on' }).then(setSettings);
  const onMode = () => api.setSettings({ mode: settings.mode === 'gen' ? 'del' : 'gen' }).then(setSettings);
  const onResetStats = () => api.resetStats().then(setStats);
  const runConsole = (text) => run(async () => {
    const r = await api.consoleCall(kind, text);
    setLog((ls) => [{ ...r, id: `${Date.now()}-${ls.length}` }, ...ls].slice(0, 10));
  });

  return h('div', { class: 'app' },
    h(TopBar, { settings, stats, onCache, onMode, onResetStats }),
    h('main', { class: 'grid' },
      h(LookupPanel, { idText, nameText, last, busy, onId: setIdText, onName: setNameText, onGetId, onSearch }),
      h(WritePanel, { writeId, writeAge: writeAgeText, lastWrite, race, busy, mode: settings.mode,
        onWriteId: setWriteId, onWriteAge: setWriteAgeText, onWrite, onRace }),
      h(TracePanel, { history, selected, summary, onSelect: setSelected }),
      h(ConsolePanel, { kind, text: consoleText, log, busy, keyHint: api.cacheKeyOf(last),
        onKind: setKind, onText: setConsoleText, onRun: () => runConsole(consoleText),
        onQuick: (q) => { setConsoleText(q); runConsole(q); } })));
}

new FunctionComponent(App, {}).mount(document.getElementById('app'));
