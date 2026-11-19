import { Ledger } from './ledger.js';
/** 입력칸의 커서 위치. 커서가 없는 요소면 null */
function caretOf(el) {
    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) {
        return null;
    }
    const { selectionStart, selectionEnd } = el;
    return selectionStart === null || selectionEnd === null ? null : { start: selectionStart, end: selectionEnd };
}
/** run 동안 컨테이너 안의 포커스 · 커서를 지킨다. 옮기기(insertBefore)는 떼었다 붙이는 것이라 포커스가 풀린다 (React도 커밋 뒤 되돌림) */
function keepFocus(container, run) {
    const active = document.activeElement;
    // 포커스가 이 컨테이너 밖에 있으면 지킬 것이 없다
    if (!(active instanceof HTMLElement || active instanceof SVGElement) || !container.contains(active)) {
        run();
        return;
    }
    // 반영 전에 커서 위치를 기억해 둔다
    const caret = caretOf(active);
    run();
    // 그 요소가 지워졌거나(isConnected false) 포커스가 그대로면 할 일 없음
    if (!active.isConnected || document.activeElement === active) {
        return;
    }
    // 옮기기 때문에 풀린 포커스와 커서를 되돌린다
    active.focus();
    if (caret !== null && (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement)) {
        active.setSelectionRange(caret.start, caret.end);
    }
}
/** 명령 목록을 반영하고 새 대장을 돌려준다 */
export function patch(commands, ledger, container, next) {
    const ctx = { ledger, container, created: new Map() };
    // 1단계: 전부 준비. 여기서 에러가 나면 아래 반영은 하나도 실행되지 않는다
    const applies = commands.map((command) => command.resolve(ctx));
    // 2단계: 전부 반영 (포커스 보존으로 감싼다)
    keepFocus(container, () => {
        for (const apply of applies) {
            apply();
        }
    });
    // 3단계: 새 트리 기준으로 대장을 처음부터 다시 짓는다. 모양이 안 맞으면 MiniReactError (반영이 잘못됐다는 뜻)
    return Ledger.build(next, container);
}
