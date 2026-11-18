import { namespaceFor } from './namespace.js';
import { setProp } from './props.js';
// 역할: VNode 하나로 진짜 DOM 노드(자손 포함)를 새로 만든다.
// 흐름: InsertNode · ReplaceNode 명령의 resolve가 부른다 → 만든 노드는 아직 문서에 붙지 않은 상태.
//   문서에 붙이는 건 나중에 명령의 반영 함수(apply)가 한 번에 한다 (불변식 2).
// 왜 따로 만들고 나중에 붙이나: 문서 밖 트리는 화면에 없으니, 만드는 동안 브라우저가 다시 계산할 일이 없고
//   반쯤 만든 화면이 보일 일도 없다. 붙이는 순간 한 번에 들어간다.
// REF: ADR-0008
/** VNode 하나로 새 DOM 노드를 만든다. parent는 namespace를 정하는 데만 쓴다 */
export function render(vnode, parent) {
    if (vnode.kind === 'text') {
        return document.createTextNode(vnode.text);
    }
    const el = document.createElementNS(namespaceFor(vnode, parent), vnode.tag);
    // 자식부터 재귀로 만들어 붙인다. 자식은 방금 만든 el을 부모로 보고 namespace를 정한다
    for (const child of vnode.children) {
        el.appendChild(render(child, el));
    }
    // 속성은 자식 뒤에 넣는다. select의 value는 option이 생긴 뒤에 넣어야 맞는 option이 선택된다
    for (const [name, value] of Object.entries(vnode.props)) {
        setProp(el, name, value);
    }
    return el;
}
