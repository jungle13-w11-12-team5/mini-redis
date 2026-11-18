import { MiniReactError } from '../engine/errors.js';
// 역할: 의존 배열이 바뀌었는지 판정 (useEffect · useMemo 공통).
// 규칙: 배열이 없으면 "매번 바뀜", 값은 하나씩 Object.is로 비교 (얕은 비교: 객체는 내용이 아니라 참조로 본다).
//   그래서 렌더마다 새로 만드는 배열 · 객체를 deps에 넣으면 늘 "바뀜"이 된다.
// REF: ADR-0010
/** 의존 값이 바뀌었는지 (Object.is로 하나씩 비교) */
export function depsChanged(prev, next) {
    // 첫 렌더(prev 없음)이거나 배열을 안 줬으면 늘 실행
    if (prev === undefined || next === undefined) {
        return true;
    }
    // 길이가 바뀌는 건 코드 실수다 (React는 경고만 한다)
    if (prev.length !== next.length) {
        throw new MiniReactError(`의존 배열의 길이가 바뀌었다 (${prev.length} → ${next.length})`);
    }
    return next.some((value, i) => !Object.is(value, prev[i]));
}
