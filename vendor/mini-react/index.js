// 공개 진입점: 저장소 밖에서 mini-react를 쓰는 쪽은 이 파일 하나만 import한다.
// 안쪽 파일 이름 · 위치가 바뀌어도 여기서 내보내는 이름은 그대로 둔다.
// 페이지(pages/*)는 계속 안쪽 경로를 쓴다. 이 파일은 아무도 import하지 않는다 (scripts/layers.test.ts)
// REF: docs/refactoring.md R1
export { MiniReactError } from './engine/errors.js';
export { Root, createRoot } from './engine/root.js';
export { Fragment, h } from './runtime/h.js';
export { useEffect, useMemo, useState } from './runtime/hooks.js';
export { FunctionComponent } from './runtime/function-component.js';
