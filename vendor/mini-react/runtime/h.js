import { MiniReactError } from '../engine/errors.js';
// 역할: 화면 모양을 적는 함수 h(type, props, ...children). JSX 대신 쓴다.
//   JSX `<div class="a">hi</div>`는 변환되면 `h('div', { class: 'a' }, 'hi')` 같은 호출이 된다 (React의 createElement).
// 핵심: h()는 런타임 노드(RNode) 객체만 만든다. 컴포넌트 함수를 부르지 않는다.
//   h(TimerPanel, props)는 "나중에 TimerPanel(props)를 불러라"라는 약속(render 클로저)만 담는다. 실제 호출은 expand.
// 흐름: 컴포넌트 함수 안에서 불린다 → 돌려준 RNode 트리를 expand가 엔진 VNode로 펼친다.
// REF: ADR-0010, docs/specs/h.md
/** Fragment: 감싸는 태그 없이 자식 여러 개를 돌려줄 때 type 자리에 넣는다 */
export const Fragment = Symbol('Fragment');
/** 화면 모양을 적는 함수: 런타임 노드를 만든다 */
export function h(type, props, ...children) {
    if (typeof type === 'function') {
        // 오버로드가 컴포넌트에는 P & KeyProp만 받게 막는다
        return componentNode(type, props);
    }
    const key = normalizeKey(props === null ? undefined : props.key);
    if (type === Fragment) {
        return { kind: 'fragment', key, children: normalizeChildren(children) };
    }
    return { kind: 'element', tag: type, props: elementProps(props), key, children: normalizeChildren(children) };
}
/** component 노드. 함수는 펼치기에서 부른다 */
function componentNode(fn, props) {
    // render는 클로저: fn과 props를 지금 붙잡아 두고, 부르는 건 나중(expand)이다
    return { kind: 'component', name: fn.name === '' ? '익명 컴포넌트' : fn.name, key: normalizeKey(props.key), render: () => fn(props) };
}
/** key 정리: 숫자는 글자로, 없으면 null */
function normalizeKey(key) {
    if (key === null || key === undefined) {
        return null;
    }
    if (typeof key === 'string' || typeof key === 'number') {
        return String(key);
    }
    throw new MiniReactError('key는 글자나 숫자만 쓸 수 있다');
}
/** element props 정리: key 제외, 숫자 → 글자, null · undefined 제외 */
function elementProps(props) {
    const out = {};
    if (props === null) {
        return out;
    }
    for (const [name, value] of Object.entries(props)) {
        // key는 DOM 속성이 아니라 diff용 이름이라 따로 뺀다
        if (name === 'key' || value === null || value === undefined) {
            continue;
        }
        out[name] = typeof value === 'number' ? String(value) : value;
    }
    return out;
}
/** 자식이 목록인지 */
function isChildList(child) {
    return Array.isArray(child);
}
/** 자식 하나를 결과 목록에 넣는다: 목록은 펼치고, 빈 값은 버리고, 글자 · 숫자는 글 노드로 */
function pushChild(out, child) {
    // {조건 && <X/>} 패턴에서 false가 오므로 boolean도 "그리지 않음"으로 버린다
    if (child === null || child === undefined || typeof child === 'boolean' || child === '') {
        return;
    }
    if (typeof child === 'string' || typeof child === 'number') {
        out.push({ kind: 'text', text: String(child) });
        return;
    }
    // items.map(...)이 만든 배열은 평평하게 편다 (재귀)
    if (isChildList(child)) {
        for (const item of child) {
            pushChild(out, item);
        }
        return;
    }
    out.push(child);
}
/** 자식 목록 정리 (React와 같은 규칙) */
export function normalizeChildren(children) {
    const out = [];
    for (const child of children) {
        pushChild(out, child);
    }
    return out;
}
