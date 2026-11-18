import { SetProp } from '../engine/commands.js';
import { MiniReactError } from '../engine/errors.js';
import { createRoot } from '../engine/root.js';
import { withFrame } from './dispatcher.js';
import { EffectQueue, runCleanups } from './effect-runner.js';
import { expand } from './expand.js';
import { snapshotHooks } from './hook-snapshot.js';
import { UpdateScheduler } from './scheduler.js';
/** 함수형 컴포넌트를 감싸 상태(훅)와 렌더를 관리한다. 루트 컴포넌트 하나에만 쓴다 */
export class FunctionComponent {
    /** 마지막으로 화면에 반영된 훅 배열. 상태는 여기에 산다 (함수가 끝나도 사라지지 않는 곳) */
    committed = [];
    fn;
    props;
    options;
    scheduler;
    /** 칸 번호 → 아직 적용 안 한 갱신 함수들. setState는 여기에 쌓이기만 한다 */
    queues = new Map();
    /** 엔진 입구. mount 전에는 null */
    root = null;
    effects = new EffectQueue();
    /** 루트 함수 실행 중인지 (렌더 중 setState 금지 검사용) */
    rendering = false;
    unmounted = false;
    renders = 0;
    setStateCalls = 0;
    constructor(fn, props, options = {}) {
        this.fn = fn;
        this.props = props;
        this.options = options;
        // 예약기가 때가 되면 부를 함수 = flush. 아직 아무것도 예약하지 않는다
        this.scheduler = new UpdateScheduler(() => this.flush());
    }
    /** 상태 저장용 hooks 배열 (마지막으로 화면에 반영된 것) */
    get hooks() {
        return this.committed;
    }
    /** 처음 렌더링: 함수 실행 → 펼치기 → 컨테이너에 반영 → effect 예약 */
    mount(container) {
        if (this.root !== null) {
            throw new MiniReactError('이미 마운트한 컴포넌트다');
        }
        this.root = createRoot(container);
        // 새 상태 없음(빈 Map) = 모든 useState가 초기값을 쓴다
        this.commit(new Map());
    }
    /** 다시 렌더링: 남은 effect 먼저 → 쌓인 상태 반영 → 함수 재실행 → diff · patch */
    update() {
        if (this.root === null || this.unmounted) {
            return;
        }
        this.effects.flush();
        this.commit(this.takeStateValues());
    }
    /** 정리 함수를 전부 부르고 화면을 비운다 */
    unmount() {
        if (this.root === null || this.unmounted) {
            return;
        }
        this.unmounted = true;
        // 아직 실행 안 한 effect는 버리고, 이미 실행한 effect의 뒷정리만 한다
        this.effects.cancel();
        runCleanups(this.committed);
        this.queues.clear();
        // 빈 목록을 그리면 diff가 전부 RemoveNode로 만든다 (지우기도 같은 길)
        this.root.render([]);
    }
    /** setter가 부른다: 갱신을 칸의 대기열에 넣고 업데이트를 예약한다 */
    enqueue(index, updater) {
        // 렌더 중 setState는 무한 렌더로 이어질 수 있어 막는다
        if (this.rendering) {
            throw new MiniReactError('렌더 중에는 setState를 부를 수 없다');
        }
        // unmount 뒤에 온 setState(타이머 등)는 조용히 무시한다
        if (this.root === null || this.unmounted) {
            return;
        }
        const queue = this.queues.get(index) ?? [];
        queue.push(updater);
        this.queues.set(index, queue);
        this.setStateCalls += 1;
        // 여러 번 불러도 예약은 한 번 (scheduler.ts)
        this.scheduler.request();
    }
    /** 예약된 업데이트. 새 상태가 전부 같으면 다시 그리지 않는다 (U3) */
    flush() {
        if (this.root === null || this.unmounted) {
            return;
        }
        // 지난 커밋의 effect가 아직 안 돌았으면 먼저 돌린다 (순서 보장, effect-runner.ts 참고)
        this.effects.flush();
        const values = this.takeStateValues();
        // 바뀐 상태가 하나도 없으면 렌더 자체를 건너뛴다 (bailout)
        if (values.size > 0) {
            this.commit(values);
        }
    }
    /** 대기열을 차례로 적용해 바뀐 상태만 모은다. 대기열은 비운다 */
    takeStateValues() {
        const values = new Map();
        for (const [index, updaters] of this.queues) {
            const hook = this.committed[index];
            if (hook === undefined || hook.kind !== 'state') {
                continue;
            }
            // 갱신 함수가 실제로 실행되는 곳. 지난 값에서 시작해 쌓인 순서대로 적용한다
            // 예: s => s+1 을 3번 → 0 → 1 → 2 → 3
            const next = updaters.reduce((value, update) => update(value), hook.value);
            // 결과가 같으면(Object.is) 바뀐 것으로 치지 않는다
            if (!Object.is(next, hook.value)) {
                values.set(index, next);
            }
        }
        this.queues.clear();
        return values;
    }
    /** 렌더 → 반영 → 훅 확정 → 보고 → effect 예약. 렌더 오류면 아무것도 바꾸지 않는다 */
    commit(stateValues) {
        const result = this.renderTree(stateValues);
        if (result === null || this.root === null) {
            return;
        }
        // Phase 1 엔진 재사용: diff → patch로 바뀐 곳만 DOM에 반영
        const commands = this.root.render(result.vnodes);
        const prev = this.committed;
        // 화면 반영까지 성공했으니 새 훅 배열을 확정한다
        this.committed = result.hooks;
        this.renders += 1;
        this.report(commands, snapshotHooks(prev, result.hooks, stateValues));
        this.effects.schedule(result.hooks);
    }
    /** 새 훅 배열에 쓰며 렌더한다 (AI 1). 사용자 코드 오류는 console.error 후 null */
    renderTree(stateValues) {
        // 작업 공간: 지난 훅 배열(prev)은 읽기만, 새 배열(next)에 쓴다. 실패하면 next만 버리면 된다
        const frame = { host: this, prev: this.committed, next: [], firstRender: this.renders === 0, stateValues, cursor: 0 };
        this.rendering = true;
        try {
            // 이 구간에서만 훅을 부를 수 있다. 루트 함수가 처음부터 다시 실행된다
            const output = withFrame(frame, () => this.fn(this.props));
            if (!frame.firstRender && frame.cursor !== this.committed.length) {
                throw new MiniReactError(`훅 개수가 이전 렌더와 다르다 (${this.committed.length} → ${frame.cursor})`);
            }
            // 펼치기는 루트 함수가 끝난 뒤 = 훅 금지 구간 (U1). 자식 컴포넌트 함수들은 여기서 실행된다
            return { hooks: frame.next, vnodes: expand(output) };
        }
        catch (error) {
            // 개발자 실수는 그대로 던진다
            if (error instanceof MiniReactError) {
                throw error;
            }
            // 사용자 코드 오류는 이번 업데이트만 버린다. 화면 · 훅은 이전 상태 그대로
            console.error('렌더 중 오류 — 이번 업데이트를 취소하고 이전 화면을 유지한다', error);
            return null;
        }
        finally {
            this.rendering = false;
        }
    }
    /** 커밋 보고 */
    report(commands, hooks) {
        // 렌더마다 새로 생기는 인라인 핸들러는 고정 래퍼의 참조만 바꾼다 (ADR-0010 AI 7) → DOM 변경으로 세지 않는다
        const isSwap = (c) => c instanceof SetProp && typeof c.value === 'function';
        const report = {
            renders: this.renders,
            setStateCalls: this.setStateCalls,
            changes: commands.filter((c) => !isSwap(c)).map((c) => c.describe()),
            handlerSwaps: commands.filter(isSwap).length,
            hooks,
        };
        this.setStateCalls = 0;
        this.options.onCommit?.(report);
    }
}
