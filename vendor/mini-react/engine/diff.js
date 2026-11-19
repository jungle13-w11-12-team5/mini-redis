import { InsertNode, MoveNode, RemoveNode, RemoveProp, ReplaceNode, SetProp, SetText, } from './commands.js';
// 역할: 옛 VNode 트리와 새 VNode 트리를 비교해 "무엇을 바꿀지" 명령 목록을 만든다.
// 흐름: Root.render가 부른다 → 만든 명령 목록은 patch가 DOM에 반영한다. diff는 DOM을 전혀 모른다 (불변식 4).
// 왜 이 방식인가: 두 트리를 정확히 비교하면 O(n³)이라 쓸 수 없다. 그래서 React와 같은 두 가정으로 O(n)에 끝낸다.
//   가정 1. 같은 부모 아래 형제끼리만 비교한다. 다른 부모로 옮겨 간 노드는 찾지 않는다.
//   가정 2. 태그가 다르면 하위 트리를 통째로 바꾼다(ReplaceNode). 그 아래는 비교하지 않는다.
//   형제 짝짓기는 key로 하고, key가 없으면 순서대로 짓는다.
// 순서 (diffChildren): ① 짝 없는 옛 노드 삭제 → ② 짝끼리 재귀 비교 · 교체 → ③ 오른쪽부터 새 노드 추가 · 이동
// REF: ADR-0008, docs/specs/diff.md
/** 부모(null이면 컨테이너)의 자식 목록 두 개를 비교해 명령 목록을 만든다 */
export function diff(parent, oldList, newList) {
    const out = [];
    diffChildren(parent, oldList, newList, out);
    return out;
}
/** element의 key, text는 null */
function keyOf(vnode) {
    return vnode.kind === 'element' ? vnode.key : null;
}
/**
 * 새 자식마다 짝이 되는 옛 자식 번호. key 있으면 key로, 없으면 key 없는 것끼리 순서대로.
 * 예: 옛 [A, B, C] → 새 [C, A, X]  (모두 key) → [2, 0, null]  (X는 새로 생김)
 */
function matchChildren(oldList, newList) {
    // 옛 목록을 한 번 훑어 두 가지 색인을 만든다: key → 번호(해시 맵이라 조회 O(1)), key 없는 것들의 번호 순서
    const keyed = new Map();
    const unkeyed = [];
    oldList.forEach((vnode, i) => {
        const key = keyOf(vnode);
        if (key === null) {
            unkeyed.push(i);
        }
        else {
            keyed.set(key, i);
        }
    });
    let next = 0;
    return newList.map((vnode) => {
        const key = keyOf(vnode);
        if (key !== null) {
            const index = keyed.get(key);
            // 같은 옛 노드를 두 번 짝짓지 않게 쓴 것은 지운다
            keyed.delete(key);
            return index ?? null;
        }
        // key 없는 노드는 "key 없는 옛 노드 중 다음 것"과 짝. 그래서 key 없이 맨 앞에 끼워 넣으면 전부 한 칸씩 밀린다
        const index = unkeyed[next];
        next += 1;
        return index ?? null;
    });
}
/** 같은 노드로 이어서 비교할 수 있는지 (태그만 비교, AI 7) */
function sameType(a, b) {
    if (a.kind === 'text' || b.kind === 'text') {
        return a.kind === b.kind;
    }
    return a.tag === b.tag;
}
/** 자식 목록 비교: 삭제 → 짝끼리 비교 · 교체 → 오른쪽부터 추가 · 이동 */
function diffChildren(parent, oldList, newList, out) {
    const matches = matchChildren(oldList, newList);
    const used = new Set(matches);
    // ① 새 목록 어디에도 짝이 없는 옛 노드는 지운다
    oldList.forEach((vnode, i) => {
        if (!used.has(i)) {
            out.push(new RemoveNode(vnode));
        }
    });
    // refs[i]: 이번 patch 뒤 i번째 자리의 DOM을 찾을 VNode (남는 것은 옛 VNode, 새로 만든 것은 새 VNode)
    // 대장은 "지금 붙어 있는" 옛 VNode로만 DOM을 찾을 수 있어서, 남는 노드는 옛 VNode로 가리킨다
    const refs = [];
    const moved = [];
    // lastPlaced: 지금까지 본 짝들의 옛 번호 중 가장 큰 값
    let lastPlaced = -1;
    // ② 새 목록을 왼쪽부터 보며 짝끼리 비교하고, 이동할 노드를 고른다
    newList.forEach((vnode, i) => {
        const oldIndex = matches[i] ?? null;
        const old = oldIndex === null ? undefined : oldList[oldIndex];
        if (oldIndex === null || old === undefined) {
            // 짝 없음 = 새 노드. ③에서 InsertNode
            refs.push(vnode);
            moved.push(false);
            return;
        }
        if (sameType(old, vnode)) {
            // 같은 종류 → DOM을 재사용하고 속성 · 자식만 비교 (재귀)
            diffNode(old, vnode, out);
            refs.push(old);
        }
        else {
            // 종류가 다름 → 하위 트리를 통째로 교체 (가정 2)
            out.push(new ReplaceNode(old, vnode));
            refs.push(vnode);
        }
        // 이동 판정: 옛 번호가 앞에서 본 것보다 작으면 "순서가 거꾸로 됐다" → 이 노드를 옮긴다
        // 예: 옛 [A,B,C,D] → 새 [D,A,B,C]: D(3)를 먼저 보면 lastPlaced=3 → A(0), B(1), C(2)가 전부 이동 = 3번
        //     (D 하나만 옮기면 되지만, 그 최소 이동을 찾으려면 LIS가 필요하다. React도 이 단순한 방식을 쓴다)
        moved.push(oldIndex < lastPlaced);
        lastPlaced = Math.max(lastPlaced, oldIndex);
    });
    // ③ 추가 · 이동: insertBefore(노드, 기준점)는 "기준점 앞에 넣기"다.
    //    오른쪽부터 놓아야 기준점(다음 자리)이 이미 제자리에 있다
    for (let i = newList.length - 1; i >= 0; i -= 1) {
        const before = refs[i + 1] ?? null;
        const vnode = newList[i];
        const ref = refs[i];
        if (vnode === undefined || ref === undefined) {
            continue;
        }
        if (matches[i] === null) {
            out.push(new InsertNode(parent, vnode, before));
        }
        else if (moved[i] === true) {
            out.push(new MoveNode(parent, ref, before));
        }
    }
}
/** 짝이 된 노드 하나 비교 */
function diffNode(old, next, out) {
    // 글 노드: 내용만 다르면 SetText
    if (old.kind === 'text' || next.kind === 'text') {
        if (old.kind === 'text' && next.kind === 'text' && old.text !== next.text) {
            out.push(new SetText(old, next.text));
        }
        return;
    }
    // 요소 노드: 속성 비교 + 자식 목록 재귀 비교
    const propCommands = diffProps(old, next);
    // select의 value는 option 명령 뒤에 (ADR-0008 AI 5). 새 option이 생기기 전에 value를 넣으면 고를 option이 없다
    if (old.tag !== 'select') {
        out.push(...propCommands);
    }
    diffChildren(old, old.children, next.children, out);
    if (old.tag === 'select') {
        out.push(...propCommands);
    }
}
/** 속성 비교. 함수는 참조로 비교하므로 렌더마다 새로 만든 인라인 핸들러는 늘 "바뀜"이다 */
function diffProps(old, next) {
    const out = [];
    // 새로 생겼거나 값이 바뀐 속성
    for (const [name, value] of Object.entries(next.props)) {
        if (old.props[name] !== value) {
            out.push(new SetProp(old, name, value));
        }
    }
    // 사라진 속성
    for (const [name, value] of Object.entries(old.props)) {
        if (!(name in next.props)) {
            out.push(new RemoveProp(old, name, value));
        }
    }
    return out;
}
