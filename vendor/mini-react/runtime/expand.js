import { MiniReactError } from '../engine/errors.js';
import { elementNode, textNode } from '../engine/vnode.js';
import { normalizeChildren } from './h.js';
// 역할: 펼치기. 런타임 노드 트리(RNode)를 엔진이 아는 VNode 트리(element · text만)로 바꾼다.
// 흐름: FunctionComponent.renderTree가 루트 함수를 실행한 "뒤에" 부른다 → 결과를 Root.render에 넘긴다.
// 핵심: 자식 컴포넌트 함수는 바로 여기서 실행된다 (expandComponent → node.render()).
//   이때는 이미 루트 렌더 구간(withFrame)이 끝나서 dispatcher가 비어 있다 → 자식에서 훅을 부르면 에러.
//   그리고 실행 결과만 남기고 컴포넌트 자체는 사라진다 → 자식이 상태를 둘 자리가 없다.
// REF: ADR-0010
/** 런타임 노드(또는 컴포넌트가 돌려준 값) → 엔진 VNode 목록. 펼칠 때마다 새 VNode를 만든다 */
export function expand(child) {
    return expandList(normalizeChildren([child]));
}
/** 런타임 노드 목록 → VNode 목록 */
function expandList(nodes) {
    const out = [];
    for (const node of nodes) {
        expandNode(node, out);
    }
    return out;
}
/** 노드 하나를 펼쳐 out에 넣는다. 하나가 여러 개(Fragment)나 0개가 될 수 있어 배열에 넣는 방식이다 */
function expandNode(node, out) {
    switch (node.kind) {
        case 'text':
            out.push(textNode(node.text));
            return;
        case 'element':
            // 자식부터 재귀로 펼친 뒤 엔진 VNode로 만든다
            out.push(elementNode({ tag: node.tag, props: node.props, key: node.key, children: expandList(node.children) }));
            return;
        case 'fragment':
            if (node.key !== null) {
                throw new MiniReactError('key 있는 Fragment는 아직 지원하지 않는다');
            }
            // Fragment는 껍데기 없이 자식만 부모 목록에 끼워 넣는다
            out.push(...expandList(node.children));
            return;
        case 'component':
            out.push(...expandComponent(node));
            return;
    }
}
/** 컴포넌트를 실행해 펼친다. key는 돌려준 element로 옮긴다 (AI 9) */
function expandComponent(node) {
    // 여기서 자식 컴포넌트 함수가 실제로 실행된다. 돌려준 값을 다시 펼친다 (컴포넌트가 컴포넌트를 돌려줄 수 있다)
    const result = expand(node.render());
    if (node.key === null) {
        return result;
    }
    // 컴포넌트에 준 key는 diff가 볼 수 있게 결과 element에 붙여야 한다. 그러려면 결과가 요소 하나여야 한다
    const only = result[0];
    if (result.length !== 1 || only === undefined || only.kind !== 'element') {
        throw new MiniReactError(`key 있는 컴포넌트 ${node.name}는 요소 하나를 돌려줘야 한다 (아직 지원하지 않음)`);
    }
    return [elementNode({ tag: only.tag, props: only.props, key: node.key, ns: only.ns, children: only.children })];
}
