import { MiniReactError } from './errors.js';
import { removeProp, setProp } from './props.js';
import { render } from './render.js';
/** 지금 붙어 있는 노드 또는 이번에 만든 노드를 찾는다. 없으면 MiniReactError (ADR-0008 AI 10) */
function lookup(ctx, vnode) {
    // 이번 patch에서 막 만든 노드가 먼저. 예: 새 노드 A 앞에 새 노드 B를 넣을 때 A는 아직 대장에 없다
    const node = ctx.created.get(vnode) ?? ctx.ledger.get(vnode);
    if (node === undefined) {
        throw new MiniReactError('patch 대상 노드를 대장에서 찾지 못했다');
    }
    return node;
}
/** 부모 VNode(null이면 컨테이너)의 DOM */
function lookupParent(ctx, parent) {
    return parent === null ? ctx.container : lookup(ctx, parent);
}
/** 대상이 Element인지 확인 */
function asElement(node) {
    if (!(node instanceof Element)) {
        throw new MiniReactError('patch 대상이 요소가 아니다');
    }
    return node;
}
/** 기준점을 찾고, 노드를 그 앞(없으면 끝)에 놓는 반영 함수를 만든다 (InsertNode · MoveNode 공유) */
function placeBefore(ctx, parentDom, node, beforeRef) {
    // 기준점 찾기는 지금(resolve), 실제로 넣기는 나중(apply)
    const before = beforeRef === null ? null : lookup(ctx, beforeRef);
    // insertBefore는 이미 문서에 있는 노드를 넘기면 "옮기기"가 된다 (떼었다 붙임 → 포커스가 풀리는 이유)
    return () => parentDom.insertBefore(node, before);
}
/** 사람이 읽는 노드 이름 */
function label(vnode) {
    return vnode.kind === 'text' ? `글 "${short(vnode.text)}"` : `<${vnode.tag}>`;
}
/** 설명용으로 줄인 글 */
function short(text) {
    const trimmed = text.replace(/\s+/g, ' ').trim();
    if (trimmed === '') {
        return text === '' ? '(빈 글)' : '(공백)';
    }
    return trimmed.length > 24 ? `${trimmed.slice(0, 24)}…` : trimmed;
}
/** 새 노드를 기준 노드 앞(없으면 끝)에 넣는다 */
export class InsertNode {
    type = 'InsertNode';
    parent;
    vnode;
    /** 이 노드 바로 뒤에 올 노드. null이면 맨 끝 */
    beforeRef;
    constructor(parent, vnode, beforeRef) {
        this.parent = parent;
        this.vnode = vnode;
        this.beforeRef = beforeRef;
    }
    /** 부모를 찾고 새 DOM을 만든 뒤, 기준점 앞에 넣는 반영 함수 */
    resolve(ctx) {
        const parentDom = lookupParent(ctx, this.parent);
        // 새 DOM(자손 포함)은 지금 만든다. 아직 문서 밖이라 화면에는 영향이 없다
        const node = render(this.vnode, parentDom);
        // 뒤에 오는 명령이 이 노드를 기준점으로 쓸 수 있게 기록
        ctx.created.set(this.vnode, node);
        return placeBefore(ctx, parentDom, node, this.beforeRef);
    }
    /** 설명 한 줄 */
    describe() {
        return `추가: ${label(this.vnode)}`;
    }
}
/** 노드를 뺀다 */
export class RemoveNode {
    type = 'RemoveNode';
    vnode;
    constructor(vnode) {
        this.vnode = vnode;
    }
    /** 대상을 찾고, 빼는 반영 함수 */
    resolve(ctx) {
        const node = lookup(ctx, this.vnode);
        return () => node.parentNode?.removeChild(node);
    }
    /** 설명 한 줄 */
    describe() {
        return `삭제: ${label(this.vnode)}`;
    }
}
/** 노드를 통째로 바꾼다 (태그 · 종류가 다를 때) */
export class ReplaceNode {
    type = 'ReplaceNode';
    oldVNode;
    newVNode;
    constructor(oldVNode, newVNode) {
        this.oldVNode = oldVNode;
        this.newVNode = newVNode;
    }
    /** 대상을 찾고 새 DOM을 만든 뒤, 바꿔 끼우는 반영 함수 */
    resolve(ctx) {
        const oldNode = lookup(ctx, this.oldVNode);
        const newNode = render(this.newVNode, oldNode.parentNode);
        ctx.created.set(this.newVNode, newNode);
        return () => oldNode.parentNode?.replaceChild(newNode, oldNode);
    }
    /** 설명 한 줄 */
    describe() {
        return `교체: ${label(this.oldVNode)} → ${label(this.newVNode)}`;
    }
}
/** 있던 노드를 기준 노드 앞(없으면 끝)으로 옮긴다 (key 짝) */
export class MoveNode {
    type = 'MoveNode';
    parent;
    vnode;
    beforeRef;
    constructor(parent, vnode, beforeRef) {
        this.parent = parent;
        this.vnode = vnode;
        this.beforeRef = beforeRef;
    }
    /** 부모 · 대상을 찾고, 기준점 앞으로 옮기는 반영 함수 */
    resolve(ctx) {
        // 새로 만들지 않고 지금 DOM을 그대로 옮긴다 → 입력값 · 포커스 같은 DOM 상태가 따라간다
        return placeBefore(ctx, lookupParent(ctx, this.parent), lookup(ctx, this.vnode), this.beforeRef);
    }
    /** 설명 한 줄 */
    describe() {
        return `이동: ${label(this.vnode)}`;
    }
}
/** 속성 하나를 넣거나 바꾼다 */
export class SetProp {
    type = 'SetProp';
    /** 대상 요소의 옛 VNode (대장은 옛 VNode로만 DOM을 찾을 수 있다) */
    target;
    name;
    value;
    constructor(target, name, value) {
        this.target = target;
        this.name = name;
        this.value = value;
    }
    /** 대상을 찾고, 속성을 넣는 반영 함수 */
    resolve(ctx) {
        const el = asElement(lookup(ctx, this.target));
        return () => setProp(el, this.name, this.value);
    }
    /** 설명 한 줄 */
    describe() {
        const shown = typeof this.value === 'function' ? '함수' : short(String(this.value));
        return `속성: <${this.target.tag}> ${this.name}="${shown}"`;
    }
}
/** 속성 하나를 뺀다 */
export class RemoveProp {
    type = 'RemoveProp';
    target;
    name;
    /** 빼기 전 값. 함수였다면 리스너를 떼야 한다 */
    prev;
    constructor(target, name, prev) {
        this.target = target;
        this.name = name;
        this.prev = prev;
    }
    /** 대상을 찾고, 속성을 빼는 반영 함수 */
    resolve(ctx) {
        const el = asElement(lookup(ctx, this.target));
        return () => removeProp(el, this.name, this.prev);
    }
    /** 설명 한 줄 */
    describe() {
        return `속성 삭제: <${this.target.tag}> ${this.name}`;
    }
}
/** 글 노드의 내용을 바꾼다 */
export class SetText {
    type = 'SetText';
    target;
    text;
    constructor(target, text) {
        this.target = target;
        this.text = text;
    }
    /** 대상 글 노드를 찾고, 내용을 바꾸는 반영 함수 */
    resolve(ctx) {
        const node = lookup(ctx, this.target);
        // 찾은 노드가 글 노드가 아니면 대장과 화면이 어긋난 것 → 반영 전에 멈춘다
        if (node.nodeType !== Node.TEXT_NODE) {
            throw new MiniReactError('patch 대상이 글 노드가 아니다');
        }
        return () => {
            node.nodeValue = this.text;
        };
    }
    /** 설명 한 줄 */
    describe() {
        return `글: "${short(this.target.text)}" → "${short(this.text)}"`;
    }
}
