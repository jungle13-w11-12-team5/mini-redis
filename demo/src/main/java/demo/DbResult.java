package demo;

/**
 * DBMS 응답 원문. SELECT는 NDJSON이고 **마지막 줄이 {"ok":true,...}일 때만** 믿는다 —
 * 스트리밍 도중 실패하면 상태 코드는 이미 200이고 마지막 줄이 {"ok":false,...}다 (Q16, DBMS §고정).
 */
public record DbResult(int status, String body) {

    String lastLine() {
        int end = body.length();
        while (end > 0 && (body.charAt(end - 1) == '\n' || body.charAt(end - 1) == '\r')) end--;
        int start = body.lastIndexOf('\n', end - 1) + 1;
        return body.substring(start, end);
    }

    /** SELECT 성공: 200 + 마지막 줄 ok:true */
    boolean selectOk() {
        return status == 200 && lastLine().startsWith("{\"ok\":true");
    }

    /** UPDATE 성공: 200 + {"ok":true,"affectedRows":[n]} */
    boolean modifyOk() {
        return status == 200 && body.startsWith("{\"ok\":true");
    }

    long rowCount() {
        return numberAfter(lastLine(), "\"rowCount\":");
    }

    long firstAffected() {
        return numberAfter(body, "\"affectedRows\":[");
    }

    private static long numberAfter(String s, String marker) {
        int i = s.indexOf(marker);
        if (i < 0) return -1;
        i += marker.length();
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        return j == i ? -1 : Long.parseLong(s.substring(i, j));
    }
}
