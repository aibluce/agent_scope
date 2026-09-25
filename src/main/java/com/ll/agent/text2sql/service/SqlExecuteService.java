package com.ll.agent.text2sql.service;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.db.SchemaIntrospector;
import com.ll.agent.text2sql.db.SqlGuard;
import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.mapper.DynamicSqlMapper;
import com.ll.agent.text2sql.util.ValueNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 只读 SQL 执行服务（MyBatis-Plus/MyBatis 的 {@link DynamicSqlMapper} + {@link SqlGuard}）。
 *
 * <p>所有出库的 SQL 都必须经过这里：先过安全网关（单条语句、只读、表白名单、自动 LIMIT），
 * 再交给 MyBatis 执行；返回值统一成 JSON 友好的 {@link QueryResult}。
 */
@Service
public class SqlExecuteService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecuteService.class);

    private final DynamicSqlMapper dynamicSqlMapper;
    private final T2SqlProperties properties;
    private final SchemaIntrospector schemaIntrospector;

    public SqlExecuteService(
            DynamicSqlMapper dynamicSqlMapper,
            T2SqlProperties properties,
            SchemaIntrospector schemaIntrospector) {
        this.dynamicSqlMapper = dynamicSqlMapper;
        this.properties = properties;
        this.schemaIntrospector = schemaIntrospector;
    }

    /** 允许访问的库：只有当前库（默认 ecommerce）。 */
    private List<String> allowedSchemas() {
        try {
            return List.of(schemaIntrospector.schemaName());
        } catch (Exception e) {
            log.warn("获取当前库名失败，跳过库白名单校验: {}", e.getMessage());
            return List.of();
        }
    }

    public T2SqlProperties properties() {
        return properties;
    }

    /** 对外入口：接收原始 SQL（用户输入或大模型输出），校验后执行。 */
    public QueryResult executeGuarded(String rawSql, Integer maxRows) {
        int limit = resolveLimit(maxRows);
        return query(prepareGuarded(rawSql, maxRows), limit);
    }

    /** 只做安全校验，返回可以执行的 SQL（不执行）；不合规时抛「不支持其他指令」。 */
    public String prepareGuarded(String rawSql, Integer maxRows) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new UnsupportedInputException(
                    "输入非法",
                    "不支持其他指令：SQL 不能为空。本接口只允许查询 " + properties.getSql().getAllowedTables(),
                    InputGuard.EXAMPLES);
        }
        try {
            return SqlGuard.prepare(
                    rawSql,
                    properties.getSql().getAllowedTables(),
                    allowedSchemas(),
                    resolveLimit(maxRows));
        } catch (SqlGuard.UnsafeSqlException e) {
            throw new UnsupportedInputException(
                    categoryOf(e.getMessage()),
                    "不支持其他指令：本接口只能查询 " + properties.getSql().getAllowedTables()
                            + " 这些表（当前库 " + allowedSchemas() + "），且只允许单条只读 SELECT。原因："
                            + e.getMessage(),
                    InputGuard.EXAMPLES);
        }
    }

    /** 把安全网关的报错归类，便于前端区分展示。 */
    private static String categoryOf(String message) {
        String m = message == null ? "" : message;
        if (m.contains("可查询范围") || m.contains("白名单") || m.contains("系统库")) {
            return "越权查询";
        }
        if (m.contains("单条")) {
            return "多条语句";
        }
        if (m.contains("只允许 SELECT")) {
            return "非查询语句";
        }
        return "写操作";
    }

    /** 执行一条已经通过安全网关的 SQL。 */
    public QueryResult query(String safeSql, int limit) {
        long start = System.currentTimeMillis();
        log.debug("执行 SQL: {}", safeSql.replaceAll("\\s+", " "));
        List<Map<String, Object>> raw;
        try {
            raw = dynamicSqlMapper.select(safeSql);
        } catch (Exception e) {
            throw new ApiException(400, "SQL 执行失败：" + rootMessage(e));
        }
        if (raw == null) {
            raw = List.of();
        }

        List<String> columns = new ArrayList<>();
        if (!raw.isEmpty()) {
            columns.addAll(raw.get(0).keySet());
        }

        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((k, v) -> normalized.put(k, ValueNormalizer.normalize(v)));
            rows.add(normalized);
        }

        boolean truncated = rows.size() >= limit;
        return new QueryResult(columns, rows, truncated, System.currentTimeMillis() - start);
    }

    /** 用 EXPLAIN 校验 SQL 语法与执行计划（不返回业务数据）。 */
    public List<String> explain(String safeSql) {
        try {
            List<Map<String, Object>> plan = dynamicSqlMapper.select("EXPLAIN " + safeSql);
            List<String> lines = new ArrayList<>();
            if (plan != null) {
                for (Map<String, Object> row : plan) {
                    StringBuilder line = new StringBuilder();
                    row.forEach((k, v) -> {
                        if (line.length() > 0) {
                            line.append(" | ");
                        }
                        line.append(k).append('=').append(ValueNormalizer.normalize(v));
                    });
                    lines.add(line.toString());
                }
            }
            return lines;
        } catch (Exception e) {
            throw new ApiException(400, "SQL 无法解析：" + rootMessage(e));
        }
    }

    /** 生效的行数上限：调用方传值不能超过服务端配置。 */
    public int resolveLimit(Integer maxRows) {
        int configured = properties.getSql().getMaxRows();
        if (maxRows == null || maxRows <= 0) {
            return configured;
        }
        return Math.min(maxRows, configured);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
