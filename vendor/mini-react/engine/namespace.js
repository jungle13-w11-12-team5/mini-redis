// 역할: 새 요소를 만들 때 어떤 namespace로 만들지 정한다.
// 왜 필요한가: 브라우저는 <svg> 안의 <circle>을 HTML 요소로 만들면 그리지 않는다.
//   SVG · MathML 요소는 createElementNS(그 namespace, 태그)로 만들어야 한다.
// 흐름: render.ts(새 DOM 만들기)가 요소마다 부른다.
// REF: ADR-0008
export const HTML_NS = 'http://www.w3.org/1999/xhtml';
export const SVG_NS = 'http://www.w3.org/2000/svg';
export const MATHML_NS = 'http://www.w3.org/1998/Math/MathML';
/** 새 요소의 namespace를 정한다 */
export function namespaceFor(vnode, parent) {
    // 1순위: VNode가 직접 알고 있으면 그대로 (dom-to-vnode가 읽어 온 경우)
    if (vnode.ns !== null) {
        return vnode.ns;
    }
    // 2순위: <svg>, <math>는 그 자체로 새 namespace의 시작점
    if (vnode.tag === 'svg') {
        return SVG_NS;
    }
    if (vnode.tag === 'math') {
        return MATHML_NS;
    }
    // 3순위: 부모를 따른다. 부모가 없으면 HTML
    if (!(parent instanceof Element)) {
        return HTML_NS;
    }
    // 예외: SVG의 <foreignObject> 안은 다시 HTML 세계다
    if (parent.namespaceURI === SVG_NS && parent.localName === 'foreignObject') {
        return HTML_NS;
    }
    return parent.namespaceURI ?? HTML_NS;
}
