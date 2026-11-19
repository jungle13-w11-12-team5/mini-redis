import { MiniReactError } from './errors.js';
/** element VNode 만들기. 빈 tag · 형제 key 중복은 MiniReactError */
export function elementNode(input) {
    if (input.tag === '') {
        throw new MiniReactError('요소의 tag가 비어 있다');
    }
    // 넘긴 배열 · 객체를 나중에 바꿔도 VNode가 따라 바뀌지 않게 복사한다 (방어적 복사, 불변식 3)
    const children = [...(input.children ?? [])];
    // key 중복은 만드는 순간 막는다. 나중에 diff에서 발견하면 어느 쪽이 진짜인지 알 수 없다
    assertUniqueKeys(children);
    return {
        kind: 'element',
        tag: input.tag,
        props: { ...input.props },
        key: input.key ?? null,
        ns: input.ns ?? null,
        children,
    };
}
/** text VNode 만들기. 공백 · 빈 글도 그대로 담는다 */
export function textNode(text) {
    return { kind: 'text', text };
}
/** 형제 사이에 같은 key가 있으면 MiniReactError (불변식 5) */
export function assertUniqueKeys(children) {
    const seen = new Set();
    for (const child of children) {
        // 글 노드와 key 없는 요소는 검사 대상이 아니다
        if (child.kind !== 'element' || child.key === null) {
            continue;
        }
        if (seen.has(child.key)) {
            throw new MiniReactError(`같은 부모 안에 key "${child.key}"가 두 번 있다`);
        }
        seen.add(child.key);
    }
}
