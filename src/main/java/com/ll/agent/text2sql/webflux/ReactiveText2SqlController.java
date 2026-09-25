package com.ll.agent.text2sql.webflux;

import com.ll.agent.text2sql.agent.Prompts;
import com.ll.agent.text2sql.agent.Text2SqlAgents;
import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.model.PipelineStep;
import com.ll.agent.text2sql.model.Text2SqlRequest;
import com.ll.agent.text2sql.model.Text2SqlResult;
import com.ll.agent.text2sql.service.SqlExecuteService;
import com.ll.agent.text2sql.service.Text2SqlService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux 版 Text2SQL 接口（响应式 SSE 对照实现）。
 *
 * <pre>
 *   GET  /api/text2sql/ask/stream?question=...   Flux&lt;ServerSentEvent&gt; 流式推送
 *   POST /api/text2sql/ask                       Mono&lt;Text2SqlResult&gt;   同步结果（包装阻塞调用）
 * </pre>
 *
 * <p><b>和 MVC 版（SseEmitter）的区别：</b>
 * <ul>
 *   <li>返回 {@code Flux<ServerSentEvent<Object>>}，事件由框架推送，不需要手动 send/complete；</li>
 *   <li>AgentScope 的 {@code streamEvents()} 本身就是 {@code Flux<AgentEvent>}，
 *       直接 {@code doOnNext} 映射成 SSE，<b>全程不阻塞容器线程</b>
 *       （MVC 版必须 {@code blockLast()}，每个进行中的请求占住一个线程）；</li>
 *   <li>超时、纠错用 Reactor 算子（{@code .timeout()} / {@code .onErrorResume()}）；</li>
 *   <li>真正阻塞的只有 MyBatis/JDBC 那几段，统一 {@code subscribeOn(boundedElastic())}。</li>
 * </ul>
 *
 * <p>注意：事件可能来自多个线程（模型回调线程、boundedElastic），因此用 {@link SerialSink}
 * 串行化写入，避免 Reactor Sinks 的 FAIL_NON_SERIALIZED 导致事件丢失或流无法结束。
 */
@RestController
@RequestMapping("/api/text2sql")
public class ReactiveText2SqlController {

    private static final Logger log = LoggerFactory.getLogger(ReactiveText2SqlController.class);

    private final Text2SqlAgents agents;
    private final Text2SqlService text2SqlService;
    private final SqlExecuteService sqlExecuteService;
    private final InputGuard inputGuard;
    private final T2SqlProperties properties;

    public ReactiveText2SqlController(
            Text2SqlAgents agents,
            Text2SqlService text2SqlService,
            SqlExecuteService sqlExecuteService,
            InputGuard inputGuard,
            T2SqlProperties properties) {
        this.agents = agents;
        this.text2SqlService = text2SqlService;
        this.sqlExecuteService = sqlExecuteService;
        this.inputGuard = inputGuard;
        this.properties = properties;
    }

    // ======================================================================
    // SSE
    // ======================================================================

    /** 流式问答：GET 方式，浏览器 EventSource / curl -N 直接可用。 */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> askStreamGet(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "includeAnswer", required = false) Boolean includeAnswer,
            @RequestParam(value = "maxRows", required = false) Integer maxRows) {
        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        request.setSessionId(sessionId);
        request.setIncludeAnswer(includeAnswer);
        request.setMaxRows(maxRows);
        return askStream(request);
    }

    /** 流式问答：POST 方式，请求体是 JSON。 */
    @PostMapping(
            value = "/ask/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> askStreamPost(@Valid @RequestBody Text2SqlRequest request) {
        return askStream(request);
    }

    /**
     * 编排流式流水线。
     *
     * <p>入口闸门仍在同步阶段执行：非查询指令直接抛异常，由
     * {@link ReactiveExceptionHandler} 转成 400（HTTP 状态码一旦开始返回流就固定成 200 了）。
     */
    private Flux<ServerSentEvent<Object>> askStream(Text2SqlRequest request) {
        String question = request.resolvedQuestion();
        inputGuard.check(question);

        String requestId = "wflux-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionId = request.resolvedSessionId(requestId);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("text2sql-webflux")
                .build();
        int limit = sqlExecuteService.resolveLimit(request.getMaxRows());
        long start = System.currentTimeMillis();

        SerialSink sink = new SerialSink();
        List<PipelineStep> steps = new ArrayList<>();
        AtomicReference<String> sqlRef = new AtomicReference<>();
        AtomicReference<QueryResult> rowsRef = new AtomicReference<>();
        AtomicReference<String> answerRef = new AtomicReference<>();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", requestId);
        meta.put("sessionId", sessionId);
        meta.put("question", question);
        sink.next("meta", meta);

        Mono<QueryResult> sqlMono = agentStage(
                        sink, agents.schemaLinker(), Prompts.schemaLink(question), ctx,
                        "① 表结构检索智能体 schema_linker", steps)
                // SQL 生成 + 安全网关 + 执行；失败时把错误回灌给模型重写一次
                .flatMap(schemaContext -> executeStage(
                                sink, ctx, question, schemaContext, null, null, steps, limit, sqlRef)
                        .onErrorResume(error -> {
                            Map<String, Object> stage = new LinkedHashMap<>();
                            stage.put("stage", "② 执行失败，触发一次纠错");
                            stage.put("status", "start");
                            sink.next("stage", stage);
                            return executeStage(
                                    sink, ctx, question, schemaContext, sqlRef.get(),
                                    rootMessage(error), steps, limit, sqlRef);
                        }));

        Mono<Void> pipeline = sqlMono.flatMap(rows -> {
            rowsRef.set(rows);
            sink.next("rows", rowsPayload(rows));
            if (!request.includeAnswerOrDefault()) {
                return Mono.empty();
            }
            return agentStage(
                            sink, agents.analyst(),
                            Prompts.analyst(question, sqlRef.get(), rows), ctx,
                            "③ 数据分析智能体 analyst", steps)
                    .doOnNext(answerRef::set)
                    .then();
        }).doOnSuccess(ignored -> sink.next("done", buildResult(
                requestId, sessionId, question, steps, sqlRef.get(), rowsRef.get(),
                answerRef.get(), start)));

        // 注意：pipeline 是 Mono<Void>，只会有 onComplete，不会有 onNext，
        // 所以必须用三参数 subscribe 的 completion 回调来结束 SSE 流
        pipeline.subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> {},
                        error -> {
                            log.error("WebFlux 流水线失败: {}", question, error);
                            sink.next("error", Map.of("error", rootMessage(error)));
                            sink.complete();
                        },
                        sink::complete);

        return sink.asFlux();
    }

    // ======================================================================
    // 同步接口（响应式包装）
    // ======================================================================

    /**
     * 同步问答：整条流水线是阻塞的，因此用 {@code Mono.fromCallable} 丢到弹性线程池，
     * 不能直接写在事件循环线程上（那会把 Netty 事件循环堵住）。
     */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Text2SqlResult> ask(@Valid @RequestBody Text2SqlRequest request) {
        return Mono.fromCallable(() -> text2SqlService.ask(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ======================================================================
    // 阶段编排
    // ======================================================================

    /** SQL 生成 + 执行阶段。 */
    private Mono<QueryResult> executeStage(
            SerialSink sink,
            RuntimeContext ctx,
            String question,
            String schemaContext,
            String previousSql,
            String error,
            List<PipelineStep> steps,
            int limit,
            AtomicReference<String> sqlRef) {
        String stageName = previousSql == null ? "② SQL 生成智能体 sql_writer" : "② SQL 重写（纠错）";
        return agentStage(
                        sink, agents.sqlWriter(),
                        Prompts.writer(question, schemaContext, previousSql, error), ctx, stageName, steps)
                .flatMap(raw -> Mono.fromCallable(() -> {
                            // 安全网关 + MyBatis 查询都是阻塞的 → boundedElastic
                            String safe = sqlExecuteService.prepareGuarded(raw, limit);
                            sqlRef.set(safe);
                            sink.next("sql", Map.of("sql", safe));
                            return sqlExecuteService.query(safe, limit);
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 一个智能体阶段：把 {@code streamEvents} 的 AgentEvent 直接映射成 SSE，
     * 返回该阶段的最终文本给下游使用。
     */
    private Mono<String> agentStage(
            SerialSink sink,
            ReActAgent agent,
            String prompt,
            RuntimeContext ctx,
            String stageName,
            List<PipelineStep> steps) {
        long t0 = System.nanoTime();
        StringBuilder streamed = new StringBuilder();
        AtomicReference<Msg> finalMsg = new AtomicReference<>();

        sink.next("stage", Map.of("stage", stageName, "status", "start"));

        return agent.streamEvents(prompt, ctx)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        streamed.append(delta.getDelta());
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("stage", stageName);
                        data.put("text", delta.getDelta());
                        sink.next("delta", data);
                    } else if (event instanceof ToolCallStartEvent tool) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("stage", stageName);
                        data.put("tool", tool.getToolCallName());
                        sink.next("tool", data);
                    } else if (event instanceof AgentResultEvent result) {
                        finalMsg.set(result.getResult());
                    }
                })
                .then(Mono.fromCallable(() -> {
                    Msg msg = finalMsg.get();
                    String text = msg != null && msg.getTextContent() != null
                            ? msg.getTextContent().trim()
                            : streamed.toString().trim();
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    steps.add(new PipelineStep(stageName, elapsedMs, preview(text)));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("stage", stageName);
                    data.put("status", "end");
                    data.put("elapsedMs", elapsedMs);
                    data.put("detail", preview(text));
                    sink.next("stage", data);
                    log.info("[{}] 耗时 {} ms", stageName, elapsedMs);
                    return text;
                }))
                .timeout(Duration.ofSeconds(properties.getAgent().getTimeoutSeconds()));
    }

    // ======================================================================
    // 工具方法
    // ======================================================================

    /**
     * 串行化的 SSE 写入器。
     *
     * <p>事件来自多个线程，而 Reactor 的 {@code Sinks} 要求串行写入，
     * 否则会返回 FAIL_NON_SERIALIZED（事件被丢、甚至流无法 complete）。这里用一把锁兜住，
     * 并保证 complete 只执行一次。
     */
    private static final class SerialSink {

        private final Sinks.Many<ServerSentEvent<Object>> sink =
                Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        synchronized void next(String event, Object data) {
            if (completed.get()) {
                return;
            }
            Sinks.EmitResult result = sink.tryEmitNext(toSse(event, data));
            if (result.isFailure()) {
                log.warn("SSE 事件发送失败 {}: {}", event, result);
            }
        }

        synchronized void complete() {
            if (completed.compareAndSet(false, true)) {
                Sinks.EmitResult result = sink.tryEmitComplete();
                if (result.isFailure()) {
                    log.warn("[SSE] complete() 失败: {}", result);
                } else {
                    log.debug("[SSE] 流已结束");
                }
            }
        }

        Flux<ServerSentEvent<Object>> asFlux() {
            return sink.asFlux()
                    .doOnCancel(() -> log.debug("[SSE] 客户端断开"))
                    .doFinally(signal -> log.debug("[SSE] 结束信号: {}", signal));
        }

        private static ServerSentEvent<Object> toSse(String event, Object data) {
            // WebFlux 的 ServerSentEvent 由编解码器负责序列化，Map/POJO 会自动转成 JSON
            return ServerSentEvent.builder().event(event).data(data).build();
        }
    }

    private Map<String, Object> rowsPayload(QueryResult rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", rows.columns);
        data.put("rows", rows.rows);
        data.put("rowCount", rows.rowCount);
        data.put("truncated", rows.truncated);
        return data;
    }

    private Text2SqlResult buildResult(
            String requestId,
            String sessionId,
            String question,
            List<PipelineStep> steps,
            String sql,
            QueryResult rows,
            String answer,
            long start) {
        Text2SqlResult result = Text2SqlResult.of(
                requestId, sessionId, question, List.copyOf(steps), System.currentTimeMillis() - start);
        result.sql = sql;
        result.answer = answer;
        if (rows != null) {
            result.withQueryResult(rows);
        }
        return result;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + " ..." : oneLine;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
