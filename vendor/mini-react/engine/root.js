import { diff } from './diff.js';
import { Ledger } from './ledger.js';
import { patch } from './patch.js';
import { assertUniqueKeys } from './vnode.js';
// 역할: 엔진의 입구. 화면을 바꾸는 길은 Root.render(vnodes) 하나뿐이다.
// 흐름: Phase 1 페이지와 Phase 2 런타임(FunctionComponent.commit)이 부른다
//   → diff(옛 VNode, 새 VNode)로 명령 목록 → patch로 DOM 반영 → "지금 VNode"를 새 것으로 교체.
// 쥐고 있는 상태 세 가지: 컨테이너(그릴 자리), 지금 화면에 반영된 VNode 목록, 대장(VNode → DOM 짝).
// REF: ADR-0008 개정 Root
/** 엔진 진입점: 컨테이너 · 지금 VNode 목록 · 대장을 쥔다 */
export class Root {
    /** 마지막으로 화면에 반영한 VNode 목록. 다음 diff의 "옛 트리"가 된다 */
    current = [];
    /** 지금 붙어 있는 VNode → DOM 노드 짝. 명령이 대상 DOM을 찾을 때 쓴다 */
    ledger;
    container;
    constructor(container) {
        this.container = container;
        // 시작할 때 한 번 비운다. 이후 DOM 변경은 명령의 반영 함수에서만 (불변식 2)
        container.replaceChildren();
        // 빈 화면 = 빈 VNode 목록. 첫 render도 diff(빈 것, 새 것)로 같은 길을 탄다
        this.ledger = Ledger.build([], container);
    }
    /** 지금 VNode 목록 */
    get vnodes() {
        return this.current;
    }
    /** 새 VNode 목록을 반영하고, 반영한 명령 목록을 돌려준다 */
    render(vnodes) {
        // 컨테이너도 부모다 (불변식 5). 요소 안의 형제는 elementNode가 이미 검사함
        assertUniqueKeys(vnodes);
        // 1. 비교: DOM은 보지 않고 VNode끼리만 (null = 부모가 컨테이너)
        const commands = diff(null, this.current, vnodes);
        // 2. 반영: 명령대로 DOM을 바꾸고, 새 트리 기준으로 대장을 다시 짓는다
        this.ledger = patch(commands, this.ledger, this.container, vnodes);
        // 3. 기억: 다음 render의 비교 기준
        this.current = vnodes;
        return commands;
    }
}
/** 컨테이너에 Root를 만든다 (React 18의 createRoot와 같은 모양) */
export function createRoot(container) {
    return new Root(container);
}
