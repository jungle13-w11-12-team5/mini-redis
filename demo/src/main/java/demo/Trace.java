package demo;

import java.util.ArrayList;
import java.util.List;

/**
 * 요청 추적 (Q25). 요청 헤더 X-Trace: 1일 때만 단계를 모아 응답 헤더 X-Trace에 JSON 배열로 싣는다.
 * 단계 = 대상(redis|minidb|demo) · 명령 · 결과 · 걸린 µs. 끄면 아무것도 모으지 않는다 → bench 수치에 안 섞임.
 */
final class Trace {
    record Step(String target, String cmd, String result, long us) {}

    static Trace off() {
        return new Trace(false);
    }

    static Trace on() {
        return new Trace(true);
    }

    private final boolean enabled;
    private final List<Step> steps = new ArrayList<>();

    private Trace(boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /** startNanos = System.nanoTime() 찍은 값 */
    void add(String target, String cmd, String result, long startNanos) {
        if (!enabled) return;
        steps.add(new Step(target, cmd, result, (System.nanoTime() - startNanos) / 1000));
    }

    void note(String target, String cmd, String result) {
        if (enabled) steps.add(new Step(target, cmd, result, 0));
    }

    List<Step> steps() {
        return steps;
    }

    String toJson() {
        var b = new StringBuilder("[");
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) b.append(',');
            b.append("{\"target\":").append(Json.ascii(s.target()))
                    .append(",\"cmd\":").append(Json.ascii(clip(s.cmd())))
                    .append(",\"result\":").append(Json.ascii(clip(s.result())))
                    .append(",\"us\":").append(s.us()).append('}');
        }
        return b.append(']').toString();
    }

    private static String clip(String s) {
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }
}
