package com.ll.agent.text2sql.controller;

import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.service.ApiException;
import com.ll.agent.text2sql.service.UnsupportedInputException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一异常处理：把业务异常映射成带 HTTP 状态码的 JSON。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 输入不是查询类指令：返回 {success:false, rejected:true, error, category, examples}。 */
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(error(message));
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(error("请求参数不正确：" + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("接口处理失败", e);
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
