// mini-stack 데모 화면. 직접 만든 mini-react(엔진 + 런타임, vendor 복사본)로 그린다.
// 규칙: 훅은 루트(App)만 · 자식은 props만 받는 함수 · 목록은 key 필수 · effect는 의존 배열 필수 · class/style은 문자열
import { h, useState, useEffect, useMemo, FunctionComponent } from '/vendor/mini-react/index.js';
import * as api from './api.js';

const HISTORY = 8;
const ROWS_SHOWN = 5;

/** X-Cache 값 → 화면 글자 · 색 */
const CACHE_TEXT = {
  hit: ['레디스', 'b-hit'],
  miss: ['DB', 'b-miss'],
  bypass: ['우회', 'b-other'],
  skip: ['캐시 안 함', 'b-other'],
  off: ['캐시 끔', 'b-other'],
  '-': ['수정', 'b-other'],
};

// ---------- 자식: 상태 없는 함수

function Badge({ cache }) {
  const [text, cls] = CACHE_TEXT[cache] ?? [cache, 'b-other'];
  return h('span', { class: `badge ${cls}` }, text);
}

function Seg({ options, value, onPick }) {
  return h('div', { class: 'seg' }, ...options.map(([v, text]) =>
    h('button', { key: v, type: 'button', class: v === value ? 'sel' : '', onClick: () => onPick(v) }, text)));
}

function TopBar({ settings, stats, onCache, onMode, onResetStats }) {
  const served = stats ? stats.hit + stats.miss + stats.bypass + stats.skip : 0;
  const ratio = served === 0 ? '-' : `${Math.round((stats.hit / served) * 100)}%`;
  return h('header', { class: 'top' },
    h('div', { class: 'title' }, '미니 레디스 캐시 데모',
      h('small', null, '화면 미니 리액트 · 서버 Java · 캐시 미니 레디스 · DB 미니 DBMS')),
    h('div', { class: 'controls' },
      h('div', { class: 'ctl' }, '캐시', h(Seg, { options: [['on', '켬'], ['off', '끔']], value: settings.cache, onPick: onCache })),
      h('div', { class: 'ctl' }, '무효화', h(Seg, { options: [['gen', '버전 번호'], ['del', '키 삭제']], value: settings.mode, onPick: onMode }))),
    h('div', { class: 'stats' },
      h('div', { class: 'stat' }, h('b', null, stats ? stats.hit : '-'), h('span', null, '레디스에서 가져옴')),
      h('div', { class: 'stat' }, h('b', null, stats ? stats.miss : '-'), h('span', null, 'DB까지 다녀옴')),
      h('div', { class: 'stat' }, h('b', null, ratio), h('span', null, '레디스 적중률')),
      h('button', { type: 'button', class: 'link', onClick: onResetStats }, '초기화')));
}

/** 서버 안 시간: 레디스 / DB 합계 (µs) */
function serverSplit(rec) {
  const s = api.summarizeTrace(rec?.steps ?? []);
  return { redis: s.by.redis.us, db: s.by.minidb.us };
}

function Result({ rec }) {
  if (rec === null) return null;
  if (rec.error) return h('div', { class: 'result' }, h('p', { class: 'err' }, `오류 ${rec.status} · ${rec.error}`));
  const { redis, db } = serverSplit(rec);
  const sum = redis + db;
  const d = rec.data;
  const rows = d ? d.rows.slice(0, ROWS_SHOWN) : [];
  return h('div', { class: 'result' },
    h('div', { class: 'result-head' },
      h(Badge, { cache: rec.cache }),
      h('span', { class: 'big' }, api.formatUs(sum)),
      h('span', { class: 'muted' }, `서버가 레디스 · DB에 쓴 시간${d ? ` · ${d.rowCount.toLocaleString()}행` : ''} · 화면까지 ${rec.ms.toFixed(0)}ms`)),
    sum === 0 ? null : h('div', null,
      h('div', { class: 'split' },
        h('div', { class: 'r', style: `width:${(redis / sum) * 100}%` }),
        h('div', { class: 'd', style: `width:${(db / sum) * 100}%` })),
      h('div', { class: 'legend' },
        h('span', null, h('i', { class: 'dot r' }), `레디스 ${api.formatUs(redis)}`),
        h('span', null, h('i', { class: 'dot d' }), `DB ${api.formatUs(db)}`))),
    rows.length === 0 ? null : h('table', null,
      h('thead', null, h('tr', null, ...d.columns.map((c) => h('th', { key: c }, c)))),
      h('tbody', null, ...rows.map((r) => h('tr', { key: String(r[0]) }, ...r.map((v, i) => h('td', { key: String(i) }, String(v))))))));
}

function LookupCard({ idText, nameText, last, busy, onId, onName, onGetId, onSearch }) {
  return h('section', { class: 'card' },
    h('h2', null, '① 조회'),
    h('p', { class: 'desc' }, '같은 조회를 두 번 누르면 두 번째는 레디스에서 가져옵니다.'),
    h('div', { class: 'row' },
      h('input', { value: idText, placeholder: 'id (1 ~ 100000)', onInput: (e) => onId(e.target.value) }),
      h('button', { type: 'button', class: 'btn', disabled: busy, onClick: onGetId }, 'id로 조회')),
    h('div', { class: 'row' },
      h('input', { value: nameText, placeholder: '이름 (예: lee)', onInput: (e) => onName(e.target.value) }),
      h('button', { type: 'button', class: 'btn', disabled: busy, onClick: onSearch }, '이름으로 검색')),
    h(Result, { rec: last }));
}

function WriteNote({ rec }) {
  if (rec === null) return null;
  if (rec.error) return h('div', { class: 'note err' }, `오류 ${rec.status} · ${rec.error}`);
  const text = rec.json.via === 'api'
    ? `DB 수정 완료 → 캐시 무효화: ${rec.json.invalidation}`
    : 'DB만 바뀌었습니다. 레디스는 모르기 때문에 같은 id를 조회하면 옛 값이 나옵니다.';
  return h('div', { class: 'note' }, text);
}

function Verdict({ race }) {
  if (race === null) return null;
  return race.stale
    ? h('div', { class: 'verdict bad' }, `옛 값(${race.age})이 캐시에 남았습니다`,
      h('small', null, '키 삭제 방식: 지운 뒤에 늦게 온 읽기가 옛 값을 다시 넣었습니다. TTL(60초)까지 유지됩니다.'))
    : h('div', { class: 'verdict good' }, `새 값(${race.age})이 나옵니다`,
      h('small', null, '버전 번호 방식: 늦게 온 옛 값은 이미 버려진 옛 버전 키에 들어가서 아무도 읽지 않습니다.'));
}

function WriteCard({ writeId, writeAge, lastWrite, race, busy, mode, onWriteId, onWriteAge, onWrite, onRace }) {
  return h('section', { class: 'card' },
    h('h2', null, '② 수정과 무효화'),
    h('p', { class: 'desc' }, 'DB만 수정하면 레디스에 옛 값이 남습니다. API로 수정하면 캐시도 함께 무효화됩니다.'),
    h('div', { class: 'row' },
      h('input', { class: 'short', value: writeId, placeholder: 'id', onInput: (e) => onWriteId(e.target.value) }),
      h('input', { class: 'short', value: writeAge, placeholder: '나이', onInput: (e) => onWriteAge(e.target.value) }),
      h('button', { type: 'button', class: 'btn', disabled: busy, onClick: () => onWrite('api') }, 'API로 수정'),
      h('button', { type: 'button', class: 'btn danger', disabled: busy, onClick: () => onWrite('direct') }, 'DB만 수정')),
    h(WriteNote, { rec: lastWrite }),
    h('hr', { class: 'divider' }),
    h('h2', null, '경합 재현'),
    h('p', { class: 'desc' }, '읽기가 DB에서 옛 값을 가져간 사이에 수정이 끝나는 순서를 일부러 만듭니다. 결과는 위쪽 무효화 방식에 따라 달라집니다.'),
    h('button', { type: 'button', class: 'btn line', disabled: busy, onClick: onRace },
      `경합 재현 — ${mode === 'gen' ? '버전 번호' : '키 삭제'} (id ${writeId || '?'})`),
    h(Verdict, { race }));
}

function LogCard({ history, open, onToggle }) {
  const max = Math.max(1, ...history.map((r) => { const s = serverSplit(r); return s.redis + s.db; }));
  return h('section', { class: 'card full' },
    h('h2', null, '③ 요청 기록'),
    h('p', { class: 'desc' },
      '막대는 서버가 ', h('i', { class: 'dot r' }), '레디스와 ', h('i', { class: 'dot d' }), 'DB에 쓴 시간입니다. 줄을 누르면 호출 순서가 보입니다.'),
    history.length === 0 ? h('p', { class: 'empty' }, '아직 요청이 없습니다.')
      : h('div', null, ...history.map((r) => {
        const s = serverSplit(r);
        return h('div', { key: r.id },
          h('div', { class: 'log-row', style: 'cursor:pointer', onClick: () => onToggle(r.id) },
            h(Badge, { cache: r.cache }),
            h('span', { class: 'label' }, r.label),
            h('div', { class: 'bars' },
              h('div', { class: 'r', style: `width:${(s.redis / max) * 100}%` }),
              h('div', { class: 'd', style: `width:${(s.db / max) * 100}%` })),
            h('span', { class: 'ms' }, api.formatUs(s.redis + s.db))),
          open !== r.id ? null : h('pre', null, r.steps.map((st) =>
            `${st.target === 'minidb' ? 'DB    ' : st.target === 'redis' ? '레디스' : '서버  '}  ${st.cmd}  →  ${st.result}  (${api.formatUs(st.us)})`).join('\n')));
      })));
}

function ConsoleFold({ open, kind, text, log, busy, onOpen, onKind, onText, onRun }) {
  return h('section', { class: 'fold' },
    h('button', { type: 'button', class: 'fold-head', onClick: onOpen }, `${open ? '▾' : '▸'} 직접 호출 (레디스 · DB에 명령 보내기, 캐시 로직을 거치지 않음)`),
    !open ? null : h('div', { class: 'card', style: 'margin-top:10px' },
      h('div', { class: 'row' },
        h(Seg, { options: [['redis', '레디스'], ['db', 'DB']], value: kind, onPick: onKind }),
        h('input', { value: text, placeholder: kind === 'redis' ? 'GET users:ver' : "SELECT * FROM users WHERE id = 1",
          onInput: (e) => onText(e.target.value), onKeydown: (e) => { if (e.key === 'Enter') onRun(); } }),
        h('button', { type: 'button', class: 'btn', disabled: busy, onClick: onRun }, '실행')),
      ...log.map((l) => h('div', { key: l.id },
        h('span', { class: l.ok ? 'muted' : 'err' }, `${l.kind === 'redis' ? '레디스' : 'DB'}> ${l.input}`),
        h('pre', null, l.output)))));
}

// ---------- 루트: 상태 · effect 전부 여기

function App() {
  const [settings, setSettings] = useState({ cache: 'on', mode: 'gen' });
  const [stats, setStats] = useState(null);
  const [idText, setIdText] = useState('777');
  const [nameText, setNameText] = useState('lee');
  const [last, setLast] = useState(null);
  const [history, setHistory] = useState([]);
  const [openLog, setOpenLog] = useState(null);
  const [writeId, setWriteId] = useState('777');
  const [writeAgeText, setWriteAgeText] = useState('30');
  const [lastWrite, setLastWrite] = useState(null);
  const [race, setRace] = useState(null);
  const [consoleOpen, setConsoleOpen] = useState(false);
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

  const shownHistory = useMemo(() => history.slice(0, HISTORY), [history]);

  const record = (rec, label) => {
    const withId = { ...rec, label: label ?? rec.label, id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}` };
    setHistory((hs) => [withId, ...hs].slice(0, HISTORY));
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

  const onGetId = () => run(async () => {
    const id = idText.trim();
    setLast(record(await api.getById(id), `id ${id} 조회`));
  });
  const onSearch = () => run(async () => {
    const name = nameText.trim();
    setLast(record(await api.searchByName(name), `이름 '${name}' 검색`));
  });
  const onWrite = (via) => run(async () => {
    const id = writeId.trim();
    const age = writeAgeText.trim();
    setLastWrite(record(await api.writeAge(id, age, via), `id ${id} ${via === 'api' ? 'API로 수정' : 'DB만 수정'} (나이 ${age})`));
  });
  const onRace = () => run(async () => {
    const age = Number(writeAgeText) || 30;
    const r = await api.runRace(writeId.trim(), age, age + 1);
    [...r.steps].reverse().forEach((s) => record(s, `경합: ${s.label}`));
    setRace(r);
  });
  const onCache = (v) => api.setSettings({ cache: v }).then(setSettings);
  const onMode = (v) => { setRace(null); api.setSettings({ mode: v }).then(setSettings); };
  const onResetStats = () => api.resetStats().then(setStats);
  const runConsole = () => run(async () => {
    const r = await api.consoleCall(kind, consoleText);
    setLog((ls) => [{ ...r, id: `${Date.now()}-${ls.length}` }, ...ls].slice(0, 3));
  });

  return h('div', { class: 'app' },
    h(TopBar, { settings, stats, onCache, onMode, onResetStats }),
    h('main', { class: 'wrap' },
      h(LookupCard, { idText, nameText, last, busy, onId: setIdText, onName: setNameText, onGetId, onSearch }),
      h(WriteCard, { writeId, writeAge: writeAgeText, lastWrite, race, busy, mode: settings.mode,
        onWriteId: setWriteId, onWriteAge: setWriteAgeText, onWrite, onRace }),
      h(LogCard, { history: shownHistory, open: openLog, onToggle: (id) => setOpenLog((o) => (o === id ? null : id)) }),
      h(ConsoleFold, { open: consoleOpen, kind, text: consoleText, log, busy,
        onOpen: () => setConsoleOpen((o) => !o), onKind: setKind, onText: setConsoleText, onRun: runConsole })));
}

new FunctionComponent(App, {}).mount(document.getElementById('app'));
