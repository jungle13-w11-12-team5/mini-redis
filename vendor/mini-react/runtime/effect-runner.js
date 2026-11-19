import { MiniReactError } from '../engine/errors.js';
// 역할: useEffect를 언제 · 어떤 순서로 실행할지 맡는다.
// 흐름: FunctionComponent.commit 마지막에 schedule() → setTimeout(0)으로 다음 태스크에 실행 예약
//   → 그 사이 브라우저가 화면을 그린다 → flush()에서 정리 함수 전부 → effect 전부 실행.
// 왜 화면 그린 뒤로 미루나: effect는 보통 화면과 상관없는 일(타이머, 제목, 데이터 요청)이다.
//   렌더 직후 바로 실행하면 그만큼 화면이 늦게 나온다 (React의 useEffect도 paint 뒤).
//   대신 effect에서 화면 크기를 재고 다시 setState하면 옛 화면이 한 번 보였다 바뀐다 (useLayoutEffect가 없는 대가).
// 격리: effect 하나가 에러를 내도 나머지는 계속 실행한다. 단 MiniReactError는 개발자 실수라 다시 던진다.
// REF: ADR-0010, ADR-0007
/** 사용자 코드 하나를 격리해 실행한다 */
export function runIsolated(run) {
    try {
        run();
    }
    catch (error) {
        if (error instanceof MiniReactError) {
            throw error;
        }
        console.error('effect 실행 중 오류 — 나머지 effect는 계속 실행한다', error);
    }
}
/** 정리 함수를 전부 부른 뒤 effect를 전부 실행한다 (호출 순서대로) */
export function runEffects(effects) {
    // 1. 지난 실행의 뒷정리 먼저 (예: clearInterval). 새 effect가 옛 effect와 겹치지 않게
    for (const effect of effects) {
        const cleanup = effect.cleanup;
        if (cleanup !== null) {
            effect.cleanup = null;
            runIsolated(cleanup);
        }
    }
    // 2. 새 effect 실행. 돌려준 함수가 있으면 다음 정리 함수로 저장
    for (const effect of effects) {
        runIsolated(() => {
            const result = effect.create();
            effect.cleanup = typeof result === 'function' ? result : null;
        });
    }
}
/** 남은 정리 함수를 전부 부른다 (unmount) */
export function runCleanups(hooks) {
    for (const hook of hooks) {
        if (hook.kind === 'effect' && hook.cleanup !== null) {
            const cleanup = hook.cleanup;
            hook.cleanup = null;
            runIsolated(cleanup);
        }
    }
}
/** 다음 태스크에 실행할 effect 대기열 (ADR-0010 U4). 다음 렌더 직전 · unmount 때 먼저 비우거나 버린다 */
export class EffectQueue {
    pending = [];
    timer = null;
    /** 의존 값이 바뀐 effect를 다음 태스크에 실행하도록 예약한다 */
    schedule(hooks) {
        const pending = hooks.filter((hook) => hook.kind === 'effect' && hook.needsRun);
        // 실행할 effect가 없으면 타이머도 걸지 않는다
        if (pending.length === 0) {
            return;
        }
        this.pending = pending;
        this.timer = setTimeout(() => this.flush(), 0);
    }
    /** 남은 effect를 지금 실행한다 (예약 시점 또는 다음 렌더 직전) */
    flush() {
        // 다음 렌더가 타이머보다 먼저 오면, 렌더 전에 여기서 먼저 실행한다.
        // 안 그러면 지난 커밋의 effect가 새 렌더 뒤에 실행돼 순서가 뒤집힌다 (React의 flushPassiveEffects)
        const effects = this.pending;
        this.cancel();
        runEffects(effects);
    }
    /** 예약을 취소하고 남은 effect를 버린다 */
    cancel() {
        if (this.timer !== null) {
            clearTimeout(this.timer);
            this.timer = null;
        }
        this.pending = [];
    }
}
