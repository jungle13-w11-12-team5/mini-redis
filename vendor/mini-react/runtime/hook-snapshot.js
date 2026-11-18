import { depsChanged } from './deps.js';
/** 칸 하나의 이번 렌더 상태 */
function statusOf(hook, prev, changed) {
    if (hook.kind === 'state') {
        if (prev === undefined) {
            return 'new';
        }
        return changed ? 'changed' : 'same';
    }
    if (hook.kind === 'effect') {
        return hook.needsRun ? 'run' : 'skip';
    }
    // memo는 값이 아니라 의존 배열로 판단한다. 다시 계산했는데 결과가 같을 수도 있기 때문이다
    const prevDeps = prev?.kind === 'memo' ? prev.deps : undefined;
    return prev === undefined || depsChanged(prevDeps, hook.deps) ? 'computed' : 'reused';
}
/** 이전 확정 훅 · 새 훅 · 이번에 바뀐 상태 칸으로 칸별 요약을 만든다 */
export function snapshotHooks(prev, next, stateValues) {
    return next.map((hook, index) => ({
        index,
        kind: hook.kind,
        value: hook.kind === 'effect' ? hook.deps : hook.value,
        status: statusOf(hook, prev[index], stateValues.has(index)),
    }));
}
