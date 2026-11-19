import { HTML_NS } from './namespace.js';
import { elementNode, textNode } from './vnode.js';
// 역할: 실제 DOM을 읽어 VNode로 바꾼다 (과제 Phase 1 요구: "브라우저 DOM → Virtual DOM").
// 흐름: Phase 1 페이지가 부른다. 페이지 로드 때 실제 영역의 샘플 HTML을 읽고, Patch 버튼 때 테스트 영역을 읽는다.
// 보안: 테스트 영역은 사용자가 마음대로 고치는 HTML이다. 그대로 실제 영역에 옮기면 코드가 실행될 수 있으므로
//   위험한 것만 버리고 console.warn으로 알린다 (on* 속성, javascript: URL, srcdoc, script · base · meta 요소).
// React와 연결: 서버가 보낸 HTML에 클라이언트 트리를 짝짓는 hydration과 같은 방향(DOM → 트리)의 일이다.
// REF: ADR-0008, ADR-0007
// 있기만 하면 참인 HTML 속성. 값 글자 대신 true로 담아야 VNode 규격(string | boolean)과 맞는다
const BOOLEAN_ATTRIBUTES = new Set([
    'allowfullscreen', 'async', 'autofocus', 'autoplay', 'checked', 'controls', 'default', 'defer',
    'disabled', 'formnovalidate', 'hidden', 'inert', 'ismap', 'itemscope', 'loop', 'multiple', 'muted',
    'nomodule', 'novalidate', 'open', 'playsinline', 'readonly', 'required', 'reversed', 'selected',
]);
// script는 실행, base는 페이지 전체 URL 기준, meta는 새로고침 · 이동을 일으킬 수 있다
const DROPPED_TAGS = new Set(['script', 'base', 'meta']);
/** 속성 값이 javascript: URL인지 (공백 · 제어 문자 무시) */
function isJavaScriptUrl(value) {
    // 브라우저는 "  java\tscript:" 같은 변형도 실행하므로 공백 · 제어 문자를 지운 뒤 본다
    return value.replace(/[\u0000-\u0020]/g, '').toLowerCase().startsWith('javascript:');
}
/** 요소의 속성 → VNode props. 위험 속성은 버리고 warn */
function readProps(el) {
    const props = {};
    for (const attr of Array.from(el.attributes)) {
        const name = attr.name;
        if (name.toLowerCase().startsWith('on') || name.toLowerCase() === 'srcdoc' || isJavaScriptUrl(attr.value)) {
            console.warn(`위험한 속성을 제거했다: <${el.localName} ${name}>`);
            continue;
        }
        props[name] = el.namespaceURI === HTML_NS && BOOLEAN_ATTRIBUTES.has(name) ? true : attr.value;
    }
    // textarea의 내용은 자식 글 노드가 아니라 value로 다룬다 (사용자가 고친 값은 property에만 있다)
    if (el.localName === 'textarea') {
        props['value'] = el.textContent ?? '';
    }
    return props;
}
/** DOM 노드 하나 → VNode. 주석 · 버리는 요소는 null */
export function domToVNode(node) {
    if (node.nodeType === Node.TEXT_NODE) {
        return textNode(node.nodeValue ?? '');
    }
    // 주석 같은 나머지 노드는 VNode 규격에 없으므로 버린다
    if (!(node instanceof Element)) {
        return null;
    }
    if (DROPPED_TAGS.has(node.localName)) {
        console.warn(`위험한 요소를 제거했다: <${node.localName}>`);
        return null;
    }
    return elementNode({
        tag: node.localName,
        props: readProps(node),
        ns: node.namespaceURI,
        children: node.localName === 'textarea' ? [] : domToVNodes(node),
    });
}
/** 부모의 자식 노드 전부 → VNode 목록 (재귀로 트리 전체를 읽는다) */
export function domToVNodes(parent) {
    const result = [];
    for (const child of Array.from(parent.childNodes)) {
        const vnode = domToVNode(child);
        if (vnode !== null) {
            result.push(vnode);
        }
    }
    return result;
}
