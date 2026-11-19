package demo;

/** 작은 JSON 문자열 도우미. str = UTF-8 그대로(본문), ascii = 비ASCII를 \\uXXXX로(HTTP 헤더 X-Trace). */
final class Json {
    private Json() {}

    static String str(String s) {
        return quote(s, false);
    }

    static String ascii(String s) {
        return quote(s, true);
    }

    private static String quote(String s, boolean asciiOnly) {
        if (s == null) return "null";
        var b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || (asciiOnly && c > 0x7e)) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
