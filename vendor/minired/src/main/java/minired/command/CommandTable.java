package minired.command;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import minired.resp.Reply;

/**
 * 명령 표 (흐름 지도 5): 명령 이름 → 처리 함수, 인자 개수 검사.
 * 오류는 모두 에러 응답으로 돌려준다. 서버와 다른 연결은 계속 동작한다 (경로 D).
 */
public final class CommandTable {
    @FunctionalInterface
    interface Handler {
        Reply run(byte[][] args);
    }

    /** arity: 양수면 정확히, 음수면 최소 |arity|개 (명령 이름 포함, Redis와 같음). */
    private record Command(String name, int arity, Handler handler) { }

    private final Map<String, Command> commands = new HashMap<>();

    public CommandTable(Commands c) {
        add("ping", -1, c::ping);
        add("set", -3, c::set);
        add("get", 2, c::get);
        add("del", -2, c::del);
        add("exists", -2, c::exists);
        add("expire", 3, c::expire);
        add("ttl", 2, c::ttl);
        add("incr", 2, c::incr);
        add("dbsize", 1, c::dbsize);
    }

    private void add(String name, int arity, Handler h) {
        commands.put(name, new Command(name, arity, h));
    }

    /** args[0]은 명령 이름. args는 1개 이상. */
    public Reply execute(byte[][] args) {
        String name = new String(args[0], StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        Command cmd = commands.get(name);
        if (cmd == null) {
            return Reply.error(unknownCommand(args));
        }
        int n = args.length;
        if ((cmd.arity() > 0 && n != cmd.arity()) || (cmd.arity() < 0 && n < -cmd.arity())) {
            return Reply.error("ERR wrong number of arguments for '" + cmd.name() + "' command");
        }
        try {
            return cmd.handler().run(args);
        } catch (RuntimeException e) {
            // 처리 함수의 버그가 서버 전체를 멈추지 않게 한다
            System.out.println("[minired] internal error in " + cmd.name() + ": " + e);
            return Reply.error("ERR internal error");
        }
    }

    private static String unknownCommand(byte[][] args) {
        StringBuilder sb = new StringBuilder("ERR unknown command '")
                .append(clip(args[0])).append("', with args beginning with: ");
        for (int i = 1; i < args.length && i <= 8; i++) {
            sb.append('\'').append(clip(args[i])).append("' ");
        }
        return sb.toString();
    }

    private static String clip(byte[] b) {
        return new String(b, 0, Math.min(b.length, 128), StandardCharsets.UTF_8);
    }
}
