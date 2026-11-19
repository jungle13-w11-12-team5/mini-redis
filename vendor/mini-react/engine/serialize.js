import { HTML_NS } from './namespace.js';
// 역할: VNode 목록을 HTML 글자로 바꾼다 (dom-to-vnode의 반대 방향).
// 흐름: Phase 1 페이지가 뒤로 · 앞으로 갈 때 테스트 영역의 글을 그 시점 VNode로 되돌리는 데 쓴다.
// 핵심: 글자를 HTML로 만들 때는 반드시 이스케이프한다. "<" 를 그대로 두면 브라우저가 태그로 읽는다(XSS 통로).
//   그 밖에 HTML 표준의 직렬화 규칙(빈 요소, raw text 요소, pre · textarea 첫 줄바꿈)을 따른다.
// React와 연결: 서버에서 컴포넌트를 HTML 글로 만드는 renderToString(SSR)과 같은 일이다.
// REF: ADR-0008 개정 serialize
// 닫는 태그가 없는 요소 (<br>, <img> ...)
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
// 안의 글을 태그로 해석하지 않는 요소. 이스케이프하면 오히려 글이 바뀐다
const RAW_TEXT_TAGS = new Set(['style', 'script', 'xmp', 'iframe', 'noembed', 'noframes', 'noscript']);
// 시작 태그 바로 뒤 줄바꿈 하나를 파서가 버리는 요소
const LEADING_NEWLINE_TAGS = new Set(['pre', 'textarea', 'listing']);
/** 글자 이스케이프 (태그 밖 글) */
function escapeText(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\u00a0/g, '&nbsp;');
}
/** 속성 값 이스케이프 (따옴표 안 글. " 를 막아야 속성 밖으로 못 빠져나간다) */
function escapeAttr(value) {
    return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/\u00a0/g, '&nbsp;');
}
/** 시작 태그의 속성 글 */
function serializeAttrs(vnode) {
    let out = '';
    for (const [name, value] of Object.entries(vnode.props)) {
        // 함수(이벤트)는 HTML 글로 나타낼 수 없고, false는 "속성 없음", textarea의 value는 안쪽 글로 나간다
        if (typeof value === 'function' || value === false || (vnode.tag === 'textarea' && name === 'value')) {
            continue;
        }
        out += value === true ? ` ${name}` : ` ${name}="${escapeAttr(value)}"`;
    }
    return out;
}
/** 요소 안쪽 글 */
function serializeInner(vnode) {
    const isHtml = vnode.ns === null || vnode.ns === HTML_NS;
    let inner;
    if (vnode.tag === 'textarea') {
        const value = vnode.props['value'];
        inner = escapeText(typeof value === 'string' ? value : '');
    }
    else if (isHtml && RAW_TEXT_TAGS.has(vnode.tag)) {
        inner = vnode.children.map((c) => (c.kind === 'text' ? c.text : '')).join('');
    }
    else {
        inner = serialize(vnode.children);
    }
    // 파서는 pre · textarea 바로 뒤 줄바꿈 하나를 버리므로 하나 더 붙여야 원래 글이 된다
    return isHtml && LEADING_NEWLINE_TAGS.has(vnode.tag) && inner.startsWith('\n') ? `\n${inner}` : inner;
}
/** VNode 목록 → HTML 글 */
export function serialize(vnodes) {
    let out = '';
    for (const vnode of vnodes) {
        if (vnode.kind === 'text') {
            out += escapeText(vnode.text);
            continue;
        }
        const isHtml = vnode.ns === null || vnode.ns === HTML_NS;
        out += `<${vnode.tag}${serializeAttrs(vnode)}>`;
        if (isHtml && VOID_TAGS.has(vnode.tag)) {
            continue;
        }
        out += `${serializeInner(vnode)}</${vnode.tag}>`;
    }
    return out;
}
