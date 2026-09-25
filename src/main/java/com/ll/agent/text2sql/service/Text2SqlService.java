package com.ll.agent.text2sql.service;

import com.ll.agent.text2sql.agent.Prompts;
import com.ll.agent.text2sql.agent.Text2SqlAgents;
import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.db.SchemaIntrospector;
import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.model.PipelineStep;
import com.ll.agent.text2sql.model.SqlExecution;
import com.ll.agent.text2sql.model.Text2SqlRequest;
import com.ll.agent.text2sql.model.Text2SqlResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Text2SQL 编排服务：把「表结构检索 → SQL 生成 → 安全执行 → 自动纠错 → 结果解读」串成一条流水线。
 *
 * <p>只有 SQL 的安全校验与真实执行放在 Java 侧（Spring + MyBatis-Plus），保证「写操作永不出库」；
 * 其余推理环节全部交给 AgentScope 的 ReAct 智能体。
 *
 * <p>推理统一走 {@code agent.streamEvents(...)}，因此同一条流水线既支持同步返回（JSON 接口），
 * 也支持实时推送（SSE 接口，通过 {@link PipelineListener} 转发事件）。
 */
@Service
public class Text2SqlService {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlService.class);

    /** 喂给分析智能体的最大行数，避免提示词过长。 */
    private static final int MAX_ROWS_TO_ANALYST = 50;

    private final T2SqlProperties properties;
    private final SqlExecuteService sqlExecuteService;
    private final SchemaIntrospector schema;
    private final Text2SqlAgents agents;
    private final InputGuard inputGuard;

    public Text2SqlService(
            T2SqlProperties properties,
            SqlExecuteService sqlExecuteService,
            SchemaIntrospector schema,
            Text2SqlAgents agents,
            InputGuard inputGuard) {
        this.properties = properties;
        this.sqlExecuteService = sqlExecuteService;
        this.schema = schema;
        this.agents = agents;
        this.inputGuard = inputGuard;
    }

    public SchemaIntrospector schemaIntrospector() {
        return schema;
    }

    public SqlExecuteService sqlExecuteService() {
        return sqlExecuteService;
    }

    // ======================================================================
    // 对外能力
    // ======================================================================

    /** 完整链路（同步）：用户输入的自然语言 → SQL → 数据 → 自然语言结论。 */
    public Text2SqlResult ask(Text2SqlRequest request) {
        return ask(request, PipelineListener.NOOP);
    }

    /** 完整链路 + 进度监听（SSE 用）。 */
    public Text2SqlResult ask(Text2SqlRequest request, PipelineListener listener) {
        String question = request.resolvedQuestion();
        // 入口闸门：只放行「查询电商业务数据」的问题，其他指令直接提示不支持
        inputGuard.check(question);
        return runPipeline(request, question, listener);
    }

    /** 已经过入口闸门校验的调用（SSE 接口在 Controller 里先同步校验，便于直接返回 400）。 */
    public Text2SqlResult askChecked(Text2SqlRequest request, PipelineListener listener) {
        return runPipeline(request, request.resolvedQuestion(), listener);
    }

    /** 只生成 SQL（不执行、不解读），适合做「SQL 生成准确率」评估。 */
    public Text2SqlResult generate(Text2SqlRequest request) {
        String question = request.resolvedQuestion();
        inputGuard.check(question);
        String requestId = newRequestId();
        String sessionId = request.resolvedSessionId(requestId);
        RuntimeContext ctx = runtimeContext(sessionId);

        List<PipelineStep> steps = new ArrayList<>();
        long start = System.currentTimeMillis();

        String schemaContext = runStage(
                steps,
                "① 表结构检索智能体 schema_linker",
                agents.schemaLinker(),
                Prompts.schemaLink(question),
                ctx,
                PipelineListener.NOOP);

        String rawSql = runStage(
                steps,
                "② SQL 生成智能体 sql_writer",
                agents.sqlWriter(),
                Prompts.writer(question, schemaContext, null, null),
                ctx,
                PipelineListener.NOOP);

        String sql = sqlExecuteService.prepareGuarded(rawSql, request.getMaxRows());

        Text2SqlResult result = Text2SqlResult.of(requestId, sessionId, question, steps, 0L);
        result.schemaContext = schemaContext;
        result.sql = sql;
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    /** 执行用户直接给定的 SQL（同样要过安全网关）。 */
    public SqlExecution execute(String rawSql, Integer maxRows) {
        int limit = sqlExecuteService.resolveLimit(maxRows);
        String sql = sqlExecuteService.prepareGuarded(rawSql, maxRows);
        return SqlExecution.of(sql, sqlExecuteService.query(sql, limit));
    }

    // ======================================================================
    // 流水线
    // ======================================================================

    private Text2SqlResult runPipeline(
            Text2SqlRequest request, String question, PipelineListener listener) {
        String requestId = newRequestId();
        String sessionId = request.resolvedSessionId(requestId);
        RuntimeContext ctx = runtimeContext(sessionId);
        listener.onStart(requestId, sessionId, question);

        List<PipelineStep> steps = new ArrayList<>();
        long start = System.currentTimeMillis();

        // ---------------- ① 表结构检索 ----------------
        String schemaContext = runStage(
                steps,
                "① 表结构检索智能体 schema_linker",
                agents.schemaLinker(),
                Prompts.schemaLink(question),
                ctx,
                listener);

        // ---------------- ② SQL 生成（含失败自动纠错） ----------------
        int maxAttempts = 1 + Math.max(0, properties.getAgent().getRepairRounds());
        int limit = sqlExecuteService.resolveLimit(request.getMaxRows());
        String sql = null;
        String lastError = null;
        QueryResult queryResult = null;
        int repairAttempts = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String stageName = attempt == 1
                    ? "② SQL 生成智能体 sql_writer"
                    : "② SQL 重写（第 " + (attempt - 1) + " 次纠错）";
            String rawSql = runStage(
                    steps,
                    stageName,
                    agents.sqlWriter(),
                    Prompts.writer(question, schemaContext, sql, lastError),
                    ctx,
                    listener);

            try {
                // 统一走安全网关：只读单条 SELECT + 表白名单 + 库白名单 + 自动 LIMIT
                String candidate = sqlExecuteService.prepareGuarded(rawSql, limit);
                listener.onSqlReady(candidate);
                QueryResult result = sqlExecuteService.query(candidate, limit);

                if (result.isEmpty() && properties.getAgent().isRetryOnEmpty() && attempt < maxAttempts) {
                    sql = candidate;
                    lastError = "上一次生成的 SQL 语法正确，但查询结果为 0 行。"
                            + "请检查过滤条件是否过严（时间范围、状态枚举、关联条件），"
                            + "在保持业务口径的前提下放宽条件后重新生成 SQL。";
                    repairAttempts++;
                    steps.add(new PipelineStep("② 结果为空，触发放宽重试", 0L, candidate));
                    continue;
                }
                sql = candidate;
                queryResult = result;
                break;
            } catch (UnsupportedInputException e) {
                lastError = "SQL 未通过安全网关：" + e.getMessage();
                repairAttempts++;
                steps.add(new PipelineStep("② SQL 被安全网关拦截", 0L, lastError));
            } catch (Exception e) {
                lastError = "SQL 执行失败：" + rootMessage(e);
                repairAttempts++;
                steps.add(new PipelineStep("② SQL 执行失败", 0L, lastError));
            }
        }

        if (sql == null || queryResult == null) {
            throw new ApiException(
                    422,
                    "未能生成可执行的 SQL。" + (lastError == null ? "" : " 最后一次原因：" + lastError));
        }

        Text2SqlResult result = Text2SqlResult.of(requestId, sessionId, question, steps, 0L);
        result.schemaContext = schemaContext;
        result.sql = sql;
        result.repairAttempts = repairAttempts;
        result.withQueryResult(queryResult);
        listener.onRows(queryResult);

        // ---------------- ③ 结果解读 ----------------
        if (request.includeAnswerOrDefault()) {
            String answer = runStage(
                    steps,
                    "③ 数据分析智能体 analyst",
                    agents.analyst(),
                    Prompts.analyst(question, sql, queryResult),
                    ctx,
                    listener);
            result.answer = answer;
        }

        result.elapsedMs = System.currentTimeMillis() - start;
        log.info("[{}] 完成：{} 行，SQL={}", requestId, result.rowCount, sql.replaceAll("\\s+", " "));
        listener.onDone(result);
        return result;
    }

    private String newRequestId() {
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private RuntimeContext runtimeContext(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).userId("text2sql-api").build();
    }

    /**
     * 执行一个智能体阶段：用流式事件拿到增量文本与工具调用，并记录耗时。
     *
     * <p>最终文本以 {@link AgentResultEvent} 携带的 Msg 为准，拿不到时退回增量拼接。
     */
    private String runStage(
            List<PipelineStep> steps,
            String stage,
            ReActAgent agent,
            String prompt,
            RuntimeContext ctx,
            PipelineListener listener) {
        long start = System.currentTimeMillis();
        StringBuilder deltas = new StringBuilder();
        Msg[] finalMsg = new Msg[1];
        listener.onStageStart(stage);
        try {
            agent.streamEvents(prompt, ctx)
                    .doOnNext(event -> handleEvent(event, stage, deltas, finalMsg, listener))
                    .blockLast(Duration.ofSeconds(properties.getAgent().getTimeoutSeconds()));

            Msg msg = finalMsg[0];
            String text = msg != null ? msg.getTextContent() : deltas.toString();
            if (msg == null && text.isEmpty()) {
                throw new ApiException(504, stage + " 超时：智能体没有在 "
                        + properties.getAgent().getTimeoutSeconds() + " 秒内返回结果");
            }
            String output = text == null ? "" : text.trim();
            long elapsed = System.currentTimeMillis() - start;
            steps.add(new PipelineStep(stage, elapsed, preview(output)));
            listener.onStageEnd(stage, elapsed, preview(output));
            log.info("[{}] 耗时 {} ms", stage, elapsed);
            return output;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, stage + " 调用失败：" + rootMessage(e), e);
        }
    }

    private void handleEvent(
            AgentEvent event, String stage, StringBuilder deltas, Msg[] finalMsg, PipelineListener listener) {
        if (event == null) {
            return;
        }
        if (event instanceof AgentResultEvent resultEvent) {
            finalMsg[0] = resultEvent.getResult();
        } else if (event instanceof TextBlockDeltaEvent deltaEvent) {
            String delta = deltaEvent.getDelta();
            if (delta != null && !delta.isEmpty()) {
                deltas.append(delta);
                listener.onDelta(stage, delta);
            }
        } else if (event instanceof ToolCallStartEvent toolEvent) {
            listener.onToolCall(stage, toolEvent.getToolCallName());
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + " ..." : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
