package com.ll.agent.text2sql.model;

import com.ll.agent.text2sql.db.QueryResult;
import java.util.List;
import java.util.Map;

/** Text2SQL 的统一响应体。 */
public class Text2SqlResult {

    public String requestId;
    public String sessionId;
    public String question;

    /** 表结构检索智能体给出的「相关表 / 关联关系 / 业务口径」。 */
    public String schemaContext;

    /** 最终执行的 SQL（已通过安全网关并补全 LIMIT）。 */
    public String sql;

    /** 数据分析智能体给出的自然语言结论。 */
    public String answer;

    public List<String> columns;
    public List<Map<String, Object>> rows;
    public int rowCount;
    public boolean truncated;

    /** SQL 生成失败后自动纠错的次数。 */
    public int repairAttempts;

    public long elapsedMs;
    public List<PipelineStep> steps;

    public static Text2SqlResult of(
            String requestId,
            String sessionId,
            String question,
            List<PipelineStep> steps,
            long elapsedMs) {
        Text2SqlResult r = new Text2SqlResult();
        r.requestId = requestId;
        r.sessionId = sessionId;
        r.question = question;
        r.steps = steps;
        r.elapsedMs = elapsedMs;
        return r;
    }

    public void withQueryResult(QueryResult qr) {
        this.columns = qr.columns;
        this.rows = qr.rows;
        this.rowCount = qr.rowCount;
        this.truncated = qr.truncated;
    }
}
