package com.ll.agent.text2sql.webflux;

import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.service.ApiException;
import com.ll.agent.text2sql.service.UnsupportedInputException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/** WebFlux 版统一异常处理：响应体结构与 MVC 版保持一致。 */
@RestControllerAdvice
public class ReactiveExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReactiveExceptionHandler.class);

    @ExceptionHandler(UnsupportedInputException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupported(UnsupportedInputException e) {
        Map<String, Object> body = error(e.getMessage());
        body.put("rejected", true);
        body.put("category", e.getCategory());
        body.put("examples", e.getExamples().isEmpty() ? InputGuard.EXAMPLES : e.getExamples());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(error(e.getMessage()));
    }

    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(error("请求参数不正确：" + e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(error(e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("WebFlux 接口处理失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("服务内部错误：" + e.getMessage()));
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message);
        return body;
    }
}
