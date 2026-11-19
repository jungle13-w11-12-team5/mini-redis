package minired.resp;

/** 형식이 깨졌거나 상한을 넘었다. 다음 명령 시작점을 알 수 없으므로 연결을 닫는다 (B8). */
public final class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
