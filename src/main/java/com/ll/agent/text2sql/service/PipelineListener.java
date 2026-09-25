package com.ll.agent.text2sql.service;

import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.model.Text2SqlResult;

/**
 * 流水线进度监听器：SSE 接口靠它在每个阶段实时把进度推给前端。
 *
 * <p>普通 JSON 接口传 {@link #NOOP} 即可，所有回调都有默认空实现。
 */
public interface PipelineListener {

    /** 空实现：JSON 接口使用。 */
    PipelineListener NOOP = new PipelineListener() {};

    /** 流水线开始。 */
    default void onStart(String requestId, String sessionId, String question) {}

    /** 某个阶段开始（表结构检索 / SQL 生成 / 结果解读）。 */
    default void onStageStart(String stage) {}

    /** 智能体调用了某个工具（list_tables / validate_sql ...）。 */
    default void onToolCall(String stage, String toolName) {}

    /** 某个阶段结束。 */
    default void onStageEnd(String stage, long elapsedMs, String detail) {}

    /** 模型输出的增量文本（用于打字机效果）。 */
    default void onDelta(String stage, String delta) {}

    /** SQL 已经生成并通过安全网关（真正执行的那条）。 */
    default void onSqlReady(String sql) {}

    /** 查询结果就绪。 */
    default void onRows(QueryResult result) {}

    /** 全流程完成。 */
    default void onDone(Text2SqlResult result) {}
}
