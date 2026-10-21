package hu.projectycg.controller.knowledge;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = KnowledgeBaseController.class)
public class KnowledgeBaseErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseErrorHandler.class);

    @ExceptionHandler(KnowledgeBaseException.class)
    public ResponseEntity<Error> handleKnowledgeBaseError(KnowledgeBaseException exception) {
        HttpStatus status = switch (exception.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case AI_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_HTTP_ERROR, INVALID_AI_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case PROMPT_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        LOG.warn("Knowledge-base request failed [{}]: {}", exception.code(), exception.getMessage());
        return ResponseEntity.status(status)
                .body(new Error(exception.code().name().toLowerCase(Locale.ROOT), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Error> handleUnreadableRequest() {
        return ResponseEntity.badRequest().body(new Error("invalid_request",
                "The request body must be valid JSON containing a gameName string."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> handleUnexpectedError(Exception exception) {
        LOG.error("Unexpected failure during knowledge-base acquisition.", exception);
        return ResponseEntity.internalServerError()
                .body(new Error("internal_error", "Knowledge-base acquisition failed unexpectedly."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Error> handleUnsupportedContentType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new Error("invalid_request", "Send the request with Content-Type: application/json."));
    }

    public record Error(String code, String message) {}
}
