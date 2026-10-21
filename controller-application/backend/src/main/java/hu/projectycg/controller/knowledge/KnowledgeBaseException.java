package hu.projectycg.controller.knowledge;

public class KnowledgeBaseException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST, AI_UNAVAILABLE, AI_TIMEOUT, AI_HTTP_ERROR, INVALID_AI_RESPONSE, PROMPT_ERROR
    }

    private final Code code;

    public KnowledgeBaseException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public KnowledgeBaseException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
