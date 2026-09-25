package com.ll.agent.text2sql.db;

import java.util.List;
import java.util.Map;

/** 查询结果：列名 + 行数据（已经是 JSON 友好的类型）。 */
public final class QueryResult {

    public final List<String> columns;
    public final List<Map<String, Object>> rows;
    public final int rowCount;
    public final boolean truncated;
    public final long elapsedMs;

    public QueryResult(
            List<String> columns,
            List<Map<String, Object>> rows,
            boolean truncated,
            long elapsedMs) {
        this.columns = columns;
        this.rows = rows;
        this.rowCount = rows.size();
        this.truncated = truncated;
        this.elapsedMs = elapsedMs;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
