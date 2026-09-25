package com.ll.agent.text2sql.controller;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.model.Text2SqlRequest;
import com.ll.agent.text2sql.model.Text2SqlResult;
import com.ll.agent.text2sql.service.PipelineListener;
import com.ll.agent.text2sql.service.Text2SqlService;
import com.ll.agent.text2sql.service.UnsupportedInputException;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Text2SQL 流式接口（SSE / text-event-stream）。
 *
 * <p>与同步接口的区别：把流水线每一步实时推给前端，用户不用等到最后才看到结果。
 *
 * <pre>
 *   GET  /api/text2sql/ask/stream?question=各省份销售额排名前5     （浏览器 EventSource 友好）
 *   POST /api/text2sql/ask/stream   {"question":"...","sessionId":"..."}
 * </pre>
 *
 * <p>事件类型（event 名 → data 结构）：
 * <pre>
 *   meta     {"requestId","sessionId","question"}          流水线开始
 *   stage    {"stage","status":"start"}                    阶段开始
 *   tool     {"stage","tool"}                              智能体调用了工具
 *   stage    {"stage","status":"end","elapsedMs","detail"} 阶段结束
 *   delta    {"stage","text"}                              模型增量文本（打字机效果）
 *   sql      {"sql"}                                       最终执行的 SQL（已过安全网关）
 *   rows     {"columns","rows","rowCount","truncated"}      查询结果
 *   done     {完整结果：answer / steps / elapsedMs ...}      全流程结束
 *   rejected {"error","category","examples"}               输入不是查询类指令
 *   error    {"error"}                                     执行过程中出错
 * </pre>
 */
@RestController
@RequestMapping("/api/text2sql")
public class Text2SqlStreamController {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlStreamController.class);

    private final Text2SqlService text2SqlService;
    private final InputGuard inputGuard;
    private final T2SqlProperties properties;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "text2sql-sse");
        thread.setDaemon(true);
        return thread;
    });

    public Text2SqlStreamController(
            Text2SqlService text2SqlService, InputGuard inputGuard, T2SqlProperties properties) {
        this.text2SqlService = text2SqlService;
        this.inputGuard = inputGuard;
        this.properties = properties;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** GET 方式（EventSource / curl -N 直接可用）。 */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreamGet(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "includeAnswer", required = false) Boolean includeAnswer,
            @RequestParam(value = "maxRows", required = false) Integer maxRows) {
        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        request.setSessionId(sessionId);
        request.setIncludeAnswer(includeAnswer);
        request.setMaxRows(maxRows);
        return stream(request);
    }

    /** POST 方式（请求体是 JSON，便于携带较长的用户输入）。 */
    @PostMapping(
            value = "/ask/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreamPost(@Valid @RequestBody Text2SqlRequest request) {
        return stream(request);
    }

    private SseEmitter stream(Text2SqlRequest request) {
        String question = request.resolvedQuestion();

        // 入口闸门放在同步阶段：非查询指令直接返回 400 + 标准拒绝体（前端更好处理）
        inputGuard.check(question);

        long timeoutMs = (properties.getAgent().getTimeoutSeconds() + 60L) * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean closed = new AtomicBoolean(false);
        SsePipelineListener listener = new SsePipelineListener(emitter, closed);

        emitter.onTimeout(() -> {
            log.warn("SSE 超时: {}", question);
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.warn("SSE 连接异常: {}", e.getMessage());
            closed.set(true);
        });
        emitter.onCompletion(() -> closed.set(true));

        executor.submit(() -> {
            try {
                // 已经过闸门校验，这里走 askChecked 避免重复调用模型复核
                text2SqlService.askChecked(request, listener);
                listener.complete();
            } catch (UnsupportedInputException e) {
                listener.sendRejected(e);
                listener.complete();
            } catch (Exception e) {
                log.error("SSE 流水线失败: {}", question, e);
                listener.sendError(e.getMessage());
                listener.complete();
            }
        });
        return emitter;
    }

    /** 把流水线回调翻译成 SSE 事件。 */
    private static final class SsePipelineListener implements PipelineListener {

        private final SseEmitter emitter;
        private final AtomicBoolean closed;

        SsePipelineListener(SseEmitter emitter, AtomicBoolean closed) {
            this.emitter = emitter;
            this.closed = closed;
        }

        @Override
        public void onStart(String requestId, String sessionId, String question) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestId", requestId);
            data.put("sessionId", sessionId);
            data.put("question", question);
            send("meta", data);
        }

        @Override
        public void onStageStart(String stage) {
            send("stage", Map.of("stage", stage, "status", "start"));
        }

        @Override
        public void onToolCall(String stage, String toolName) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", stage);
            data.put("tool", toolName);
            send("tool", data);
        }

        @Override
        public void onStageEnd(String stage, long elapsedMs, String detail) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", stage);
            data.put("status", "end");
            data.put("elapsedMs", elapsedMs);
            data.put("detail", detail);
            send("stage", data);
        }

        @Override
        public void onDelta(String stage, String delta) {
            send("delta", Map.of("stage", stage, "text", delta));
        }

        @Override
        public void onSqlReady(String sql) {
            send("sql", Map.of("sql", sql));
        }

        @Override
        public void onRows(QueryResult result) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", result.columns);
            data.put("rows", result.rows);
            data.put("rowCount", result.rowCount);
            data.put("truncated", result.truncated);
            send("rows", data);
        }

        @Override
        public void onDone(Text2SqlResult result) {
            send("done", result);
        }

        void sendRejected(UnsupportedInputException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", false);
            data.put("rejected", true);
            data.put("category", e.getCategory());
            data.put("error", e.getMessage());
            data.put("examples", e.getExamples().isEmpty() ? InputGuard.EXAMPLES : e.getExamples());
            send("rejected", data);
        }

        void sendError(String message) {
            send("error", Map.of("success", false, "error", message == null ? "执行失败" : message));
        }

        void complete() {
            if (!closed.getAndSet(true)) {
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 客户端已断开
                }
            }
        }

        private void send(String event, Object data) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开或 emitter 已关闭：标记关闭，后续事件直接丢弃
                closed.set(true);
                log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }
}
