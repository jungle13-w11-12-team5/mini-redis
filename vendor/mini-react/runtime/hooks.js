import { depsChanged } from './deps.js';
import { takeSlot } from './dispatcher.js';
/** 함수형 업데이트인지 */
function isUpdater(action) {
    return typeof action === 'function';
}
/** 초기값이 지연 계산 함수인지 */
function isInitializer(initial) {
    return typeof initial === 'function';
}
/** setter 만들기: 갱신을 함수로 바꿔 칸의 대기열에 넣는다. 첫 렌더에 칸마다 한 번만 만든다 */
function createSetter(host, index) {
    // host(컴포넌트)와 index(칸 번호)를 클로저에 가둔다. setter는 렌더 밖(클릭 핸들러)에서 불리므로 dispatcher를 쓸 수 없다
    return (action) => {
        // 값이든 함수든 "이전 값 → 새 값" 함수 한 모양으로 감싼다. 값은 여기서 계산하지 않는다 (flush가 차례로 적용)
        // 같은 칸은 늘 같은 훅이 쓰므로 저장된 값의 타입은 T다 (훅 순서 규칙)
        host.enqueue(index, (prev) => (isUpdater(action) ? action(prev) : action));
    };
}
/** 상태 하나와 그 setter */
export function useState(initial) {
    const { frame, index, prev } = takeSlot('useState', 'state');
    const previous = prev?.kind === 'state' ? prev : undefined;
    let value;
    if (previous === undefined) {
        // 첫 렌더: 초기값. 함수면 지금 한 번만 부른다 (지연 초기화)
        value = isInitializer(initial) ? initial() : initial;
    }
    else {
        // 다음 렌더: flush가 계산해 둔 새 값이 있으면 그것, 없으면 지난 값 그대로
        value = frame.stateValues.has(index) ? frame.stateValues.get(index) : previous.value;
    }
    // setter는 처음 만든 것을 계속 쓴다 → 렌더마다 같은 함수 (deps에 넣어도 effect가 다시 돌지 않는다)
    const setter = previous?.setter ?? createSetter(frame.host, index);
    frame.next[index] = { kind: 'state', value, setter };
    // 같은 칸은 늘 같은 훅이 쓰므로 값 · setter의 타입은 처음 만든 그대로다
    return [value, setter];
}
/** 화면에 반영된 뒤 실행할 일. 의존 값이 바뀔 때만 다시 실행 */
export function useEffect(create, deps) {
    const { frame, index, prev } = takeSlot('useEffect', 'effect');
    const previous = prev?.kind === 'effect' ? prev : undefined;
    // 여기서는 실행하지 않고 "실행할지(needsRun)"만 적는다. 실행은 커밋 뒤 EffectQueue가 한다
    frame.next[index] = {
        kind: 'effect',
        deps,
        create,
        // 지난 실행의 정리 함수를 넘겨받는다. 다음 실행 직전이나 unmount 때 부른다
        cleanup: previous?.cleanup ?? null,
        needsRun: depsChanged(previous?.deps, deps),
    };
}
/** 계산 결과를 저장해 두고 의존 값이 같으면 다시 쓴다 */
export function useMemo(compute, deps) {
    const { frame, index, prev } = takeSlot('useMemo', 'memo');
    const previous = prev?.kind === 'memo' ? prev : undefined;
    // 의존 값이 그대로면 지난 결과를 재사용, 바뀌었으면 다시 계산
    const value = previous !== undefined && !depsChanged(previous.deps, deps) ? previous.value : compute();
    frame.next[index] = { kind: 'memo', deps, value };
    // 같은 칸은 늘 같은 훅이 쓰므로 저장된 값의 타입은 T다
    return value;
}
