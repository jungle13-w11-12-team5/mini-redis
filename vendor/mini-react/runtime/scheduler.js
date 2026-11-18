// 역할: 업데이트 예약기. setState를 여러 번 불러도 렌더는 한 번만 일어나게 묶는다 (배칭).
// 흐름: FunctionComponent.enqueue가 setState마다 request()를 부른다 → 처음 한 번만 마이크로태스크를 예약한다
//   → 지금 실행 중인 코드(클릭 핸들러 등)가 끝나고 호출 스택이 비면 run(= flush)이 한 번 실행된다.
// 왜 마이크로태스크인가: 동기로 바로 렌더하면 setState 4번에 렌더 4번이 된다.
//   setTimeout으로 미루면 그 사이 브라우저가 옛 화면을 한 번 더 그릴 수 있다.
//   마이크로태스크는 "지금 코드 직후, 화면 그리기 전"에 실행되므로 둘 다 피한다.
// React와 연결: React 18의 자동 배칭(automatic batching)과 같은 효과.
// REF: ADR-0010
/** 업데이트 예약기 */
export class UpdateScheduler {
    /** 이미 예약해 둔 실행이 있는지 */
    scheduled = false;
    run;
    constructor(run) {
        this.run = run;
    }
    /** 업데이트를 요청한다. 이미 예약돼 있으면 아무것도 하지 않는다 */
    request() {
        if (this.scheduled) {
            return;
        }
        this.scheduled = true;
        queueMicrotask(() => {
            // 실행 전에 풀어야, run 안에서 다시 setState가 불리면(effect 등) 새로 예약할 수 있다
            this.scheduled = false;
            this.run();
        });
    }
}
