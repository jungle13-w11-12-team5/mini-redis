import { MiniReactError } from './errors.js';
// 역할: 대장. "지금 화면에 붙어 있는 VNode 객체 → 그 VNode로 만든 실제 DOM 노드" 짝을 들고 있다.
// 흐름: 명령의 resolve가 대상 DOM을 찾을 때 쓴다 → patch 마지막 단계에서 새 트리 기준으로 다시 짓는다.
// 왜 필요한가: diff는 DOM을 모르고 VNode만 안다(불변식 4). 그래서 "이 VNode의 글을 바꿔라"를
//   실제 DOM 노드로 바꿔 줄 번역표가 필요하다. React에서는 fiber.stateNode가 이 역할이다.
// 왜 위치가 아니라 VNode 객체를 키로 쓰나: 위치("2번째 자식")는 추가 · 삭제 · 이동이 섞이면 patch 도중에 바뀐다.
//   VNode 객체는 불변이라 patch 내내 같은 대상을 가리킨다.
// 비용: patch마다 트리 전체를 다시 걷는다(O(N)). 단순한 대신 큰 트리에서는 낭비다.
// REF: ADR-0008
/** 대장: 지금 화면에 붙어 있는 VNode와 DOM 노드의 짝 */
export class Ledger {
    // WeakMap: VNode가 더 이상 쓰이지 않으면 짝도 GC가 같이 치운다
    nodes = new WeakMap();
    /** VNode의 DOM 노드. 없으면 undefined */
    get(vnode) {
        return this.nodes.get(vnode);
    }
    /** 새 트리와 컨테이너 DOM을 나란히 걸으며 짝을 새로 만든다. 모양이 다르면 MiniReactError */
    static build(vnodes, container) {
        const ledger = new Ledger();
        ledger.link(vnodes, container);
        return ledger;
    }
    /** 자식 목록끼리 짝짓기. 같은 번호끼리 짝 → 요소면 그 자식으로 재귀 */
    link(vnodes, parent) {
        const doms = parent.childNodes;
        // 짝짓기와 동시에 검사도 한다: 화면 DOM이 VNode 트리와 모양이 같아야 한다 (불변식 1)
        if (doms.length !== vnodes.length) {
            throw new MiniReactError(`대장 갱신 실패: 자식 수가 다르다 (VNode ${vnodes.length}, DOM ${doms.length})`);
        }
        vnodes.forEach((vnode, i) => {
            const dom = doms[i];
            if (dom === undefined || !matches(vnode, dom)) {
                throw new MiniReactError(`대장 갱신 실패: ${i}번째 노드 종류가 다르다`);
            }
            this.nodes.set(vnode, dom);
            if (vnode.kind === 'element') {
                this.link(vnode.children, dom);
            }
        });
    }
}
/** VNode와 DOM 노드의 종류 · 태그가 같은지 */
function matches(vnode, dom) {
    if (vnode.kind === 'text') {
        return dom.nodeType === Node.TEXT_NODE;
    }
    return dom instanceof Element && dom.localName === vnode.tag;
}
