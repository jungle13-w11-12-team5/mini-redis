// 역할: 이 라이브러리가 던지는 에러 클래스. 프로젝트 전체에 이것 하나뿐이다.
// 언제 던지나: 개발자가 규칙을 어겼거나(훅 순서, key 중복 등), 화면과 VNode가 어긋날 위험이 있을 때.
// 왜 하나만: 사용자 코드 오류(렌더 · effect 안의 일반 Error)와 구분하려고. 런타임은 일반 Error는
//   잡아서 console.error로 격리하고, MiniReactError는 절대 삼키지 않고 다시 던진다.
// REF: ADR-0007
/** 개발자 실수 · 화면과 VNode가 어긋날 위험을 알리는 에러 */
export class MiniReactError extends Error {
    name = 'MiniReactError';
}
