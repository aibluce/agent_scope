package com.ll.agent.text2sql.model;

import com.ll.agent.text2sql.db.QueryResult;

/** 直接执行 SQL 的结果（POST /api/sql/execute）。 */
public class SqlExecution {

    public String sql;
    public java.util.List<String> columns;
    public java.util.List<java.util.Map<String, Object>> rows;
    public int rowCount;
    public boolean truncated;
    public long elapsedMs;

    public static SqlExecution of(String sql, QueryResult qr) {
        SqlExecution e = new SqlExecution();
        e.sql = sql;
        e.columns = qr.columns;
        e.rows = qr.rows;
        e.rowCount = qr.rowCount;
        e.truncated = qr.truncated;
        e.elapsedMs = qr.elapsedMs;
        return e;
    }
}
