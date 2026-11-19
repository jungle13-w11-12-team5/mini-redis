import { MiniReactError } from './errors.js';
// 요소 → (이벤트 이름 → 리스너). WeakMap이라 요소가 문서에서 빠지고 버려지면 항목도 같이 사라진다
const listeners = new WeakMap();
/** 이벤트 속성 이름이면 이벤트 이름 (`onClick` → `click`), 아니면 null */
function eventName(name) {
    return name.length > 2 && name.toLowerCase().startsWith('on') ? name.slice(2).toLowerCase() : null;
}
/** 이벤트 핸들러 연결. 요소 · 이벤트마다 고정 래퍼 하나, 교체는 참조만 바꿈 */
function setListener(el, type, handler) {
    let map = listeners.get(el);
    if (map === undefined) {
        map = new Map();
        listeners.set(el, map);
    }
    const existing = map.get(type);
    if (existing !== undefined) {
        // 이미 래퍼가 달려 있다 → 래퍼가 부를 함수만 바꾼다. DOM은 건드리지 않는다
        existing.handler = handler;
        return;
    }
    // 처음 다는 경우: 래퍼는 "지금 entry.handler를 불러라"만 한다. 그래서 handler를 나중에 바꿔도 래퍼는 그대로 쓸 수 있다
    const entry = { handler, wrapper: (event) => entry.handler(event) };
    map.set(type, entry);
    el.addEventListener(type, entry.wrapper);
}
/** 이벤트 핸들러 떼기 */
function removeListener(el, type) {
    const entry = listeners.get(el)?.get(type);
    if (entry !== undefined) {
        // removeEventListener는 add 때와 "같은 함수"를 넘겨야 떼진다 → 저장해 둔 wrapper를 넘긴다
        el.removeEventListener(type, entry.wrapper);
        listeners.get(el)?.delete(type);
    }
}
/** property로도 넣어야 화면이 바뀌는 속성 (사용자가 만진 폼). true를 돌려주면 attribute는 건너뛴다 */
function setFormProperty(el, name, value) {
    if (name === 'value' && (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement)) {
        el.value = typeof value === 'string' ? value : '';
        // input만 attribute도 같이 쓴다. textarea · select에는 value attribute가 없다
        return !(el instanceof HTMLInputElement);
    }
    if (name === 'checked' && el instanceof HTMLInputElement) {
        el.checked = value === true;
    }
    if (name === 'selected' && el instanceof HTMLOptionElement) {
        el.selected = value === true;
    }
    return false;
}
/** 속성 하나를 DOM에 넣는다 */
export function setProp(el, name, value) {
    const type = eventName(name);
    // 경우 1: 함수 값 → 이벤트 리스너
    if (typeof value === 'function') {
        if (type === null) {
            throw new MiniReactError(`함수 값은 on으로 시작하는 이벤트 속성에만 쓸 수 있다: ${name}`);
        }
        setListener(el, type, value);
        return;
    }
    // 글자 핸들러는 attribute가 되는 순간 실행될 수 있다 (React도 거부)
    if (type !== null && typeof value === 'string') {
        throw new MiniReactError(`이벤트 속성에는 함수만 쓸 수 있다: ${name}`);
    }
    // 경우 2: 폼 값 → property. textarea · select의 value는 attribute가 없어 property만 쓴다
    if (setFormProperty(el, name, value)) {
        return;
    }
    // 경우 3: 보통 attribute. boolean 속성은 있으면 참(값은 빈 글), 없으면 거짓
    if (value === false) {
        el.removeAttribute(name);
    }
    else {
        el.setAttribute(name, value === true ? '' : value);
    }
}
/** 속성 하나를 DOM에서 뺀다. prev는 빼기 전 값 (함수였는지 알아야 리스너를 뗀다) */
export function removeProp(el, name, prev) {
    const type = eventName(name);
    if (typeof prev === 'function') {
        if (type !== null) {
            removeListener(el, type);
        }
        return;
    }
    // 폼 property도 비워야 화면에서 사라진다 (attribute만 지우면 화면 값은 남는다)
    setFormProperty(el, name, name === 'value' ? '' : false);
    el.removeAttribute(name);
}
