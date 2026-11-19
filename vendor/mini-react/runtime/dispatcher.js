import { MiniReactError } from '../engine/errors.js';
// 지금 렌더 중인 작업 공간. 렌더 밖(자식 펼치기 · 이벤트 핸들러)에서는 null
let current = null;
/** run 동안만 frame을 지금 렌더로 둔다 */
export function withFrame(frame, run) {
    current = frame;
    try {
        return run();
    }
    finally {
        // 렌더가 에러로 끝나도 반드시 비운다. 안 비우면 다음 훅 호출이 엉뚱한 frame에 쓴다
        current = null;
    }
}
/** 훅 칸 하나를 차지한다. 렌더 밖이거나 칸의 종류가 바뀌었으면 MiniReactError */
export function takeSlot(hookName, kind) {
    if (current === null) {
        throw new MiniReactError(`${hookName}는 루트 컴포넌트를 렌더하는 동안에만 부를 수 있다 (자식 컴포넌트 · 이벤트 핸들러 안은 금지)`);
    }
    const frame = current;
    // 호출 순서 = 칸 번호
    const index = frame.cursor;
    frame.cursor += 1;
    const prev = frame.prev[index];
    // 지난 렌더의 같은 번호 칸과 종류가 다르면 훅 순서가 바뀐 것 (조건문 안의 훅 등)
    if (!frame.firstRender && (prev === undefined || prev.kind !== kind)) {
        throw new MiniReactError(`훅 순서가 이전 렌더와 다르다 (${index}번째 칸: ${prev?.kind ?? '없음'} → ${kind}). 조건문 안에서 훅을 부르지 않았는지 확인`);
    }
    return { frame, index, prev };
}
