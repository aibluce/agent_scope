package com.ll.agent.text2sql.agent;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.db.SchemaIntrospector;
import com.ll.agent.text2sql.service.ApiException;
import com.ll.agent.text2sql.service.SqlExecuteService;
import com.ll.agent.text2sql.util.Json;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 暴露给智能体的数据库工具集（Spring Bean）。
 *
 * <p>这是 Text2SQL 智能体与真实数据库之间的唯一通道：
 * <ul>
 *   <li>元数据工具（list_tables / describe_table / search_columns / sample_rows）帮助模型确认真实表名、
 *       字段名与枚举取值，避免幻觉；</li>
 *   <li>{@code validate_sql} 用 EXPLAIN 做无副作用的语法校验；</li>
 *   <li>{@code execute_sql} 是唯一真正取数的入口，且必须通过 {@link SqlGuard}。</li>
 * </ul>
 *
 * <p>数据访问全部走 MyBatis：{@link SqlExecuteService} → {@code DynamicSqlMapper}。
 * SQL 的安全校验在 {@code SqlGuard} 中完成，这里只负责把结果喂给模型。
 */
@Component
public class DbTools {

    private static final Logger log = LoggerFactory.getLogger(DbTools.class);

    private final SchemaIntrospector schema;
    private final SqlExecuteService sqlExecuteService;
    private final T2SqlProperties properties;
    private final AtomicInteger executeCount = new AtomicInteger();

    public DbTools(
            SchemaIntrospector schema, SqlExecuteService sqlExecuteService, T2SqlProperties properties) {
        this.schema = schema;
        this.sqlExecuteService = sqlExecuteService;
        this.properties = properties;
    }

    public int executeCount() {
        return executeCount.get();
    }

    // ------------------------------------------------------------------ 元数据

    @Tool(
            name = "list_tables",
            description = "列出数据库中所有可查询的表：表名、表注释、数据量。生成 SQL 前应先用它确认可用表。",
            readOnly = true)
    public String listTables() {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (SchemaIntrospector.Table t : schema.tables()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", t.name);
            item.put("comment", t.comment);
            item.put("rowCount", t.rowCount);
            item.put("columns", t.columns.stream().map(c -> c.name).toList());
            tables.add(item);
        }
        log.info("[tool] list_tables -> {} 张表", tables.size());
        return Json.write(Map.of("database", schema.schemaName(), "tables", tables));
    }

    @Tool(
            name = "describe_table",
            description = "查询某张表的完整字段定义：字段名、类型、是否可空、主键、字段注释。"
                    + "写 SQL 前必须调用它确认真实字段名，不要凭猜测写字段。",
            readOnly = true)
    public String describeTable(
            @ToolParam(name = "table_name", description = "表名，例如 t_order") String tableName) {
        log.info("[tool] describe_table({})", tableName);
        try {
            return schema.renderTable(tableName);
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(
            name = "search_columns",
            description = "按关键字模糊搜索字段，会同时匹配字段名与字段注释（支持中文），"
                    + "用于定位「成交额」「省份」「商品类目」「会员等级」等业务口径对应的真实字段。",
            readOnly = true)
    public String searchColumns(
            @ToolParam(name = "keyword", description = "关键字，中英文均可，例如 金额 / 省份 / 商品 / status")
                    String keyword) {
        log.info("[tool] search_columns({})", keyword);
        List<String> hits = schema.searchColumns(keyword);
        if (hits.isEmpty()) {
            return "未匹配到任何字段，请换一个关键字，或用 list_tables / describe_table 查看全量结构。";
        }
        return String.join("\n", hits);
    }

    @Tool(
            name = "sample_rows",
            description = "查看某张表的前几行真实数据，用于理解枚举字段"
                    + "（如 order_status / channel / member_level / category_name）的取值含义与数据格式。",
            readOnly = true)
    public String sampleRows(
            @ToolParam(name = "table_name", description = "表名，例如 t_order") String tableName,
            @ToolParam(name = "limit", description = "返回行数，默认 3，最大 10", required = false)
                    Integer limit) {
        int n = limit == null ? 3 : Math.max(1, Math.min(limit, 10));
        log.info("[tool] sample_rows({}, {})", tableName, n);
        SchemaIntrospector.Table table = schema.find(tableName);
        if (table == null) {
            return "ERROR: 表不存在 " + tableName + "，可用表: " + schema.tableNames();
        }
        try {
            // 表名来自 information_schema 校验过的元数据，且只拼 LIMIT 常量，无注入面
            QueryResult r = sqlExecuteService.query("SELECT * FROM `" + table.name + "` LIMIT " + n, n);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", table.name);
            body.put("columns", r.columns);
            body.put("rows", r.rows);
            return Json.write(body);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ SQL 相关

    @Tool(
            name = "validate_sql",
            description = "校验一条 MySQL 查询语句：先做只读安全检查，再用 EXPLAIN 验证语法。"
                    + "返回 OK 表示语法正确可以执行；返回 ERROR 时需要根据错误信息修正 SQL 后重新校验。"
                    + "注意：本工具不返回业务数据，不要用它来取数。",
            readOnly = true)
    public String validateSql(@ToolParam(name = "sql", description = "待校验的 SELECT 语句") String sql) {
        log.info("[tool] validate_sql: {}", oneLine(sql));
        try {
            String safe = sqlExecuteService.prepareGuarded(sql, null);
            List<String> plan = sqlExecuteService.explain(safe);
            return "OK：语法校验通过。\nSQL: " + safe + "\n执行计划:\n" + String.join("\n", plan);
        } catch (ApiException e) {
            return "ERROR：SQL 未通过安全校验或无法执行 —— " + e.getMessage();
        } catch (Exception e) {
            return "ERROR：SQL 执行计划生成失败 —— " + rootMessage(e)
                    + "\n请检查表名、字段名、函数用法是否符合 MySQL 8.0 语法。";
        }
    }

    @Tool(
            name = "execute_sql",
            description = "执行一条只读 SELECT 查询并返回结果数据（JSON 格式，受行数上限限制）。"
                    + "只有在你确认业务口径正确后才调用本工具取数；返回 ERROR 时需修正 SQL 后重新调用。",
            readOnly = true)
    public String executeSql(@ToolParam(name = "sql", description = "只读的 SELECT 查询语句") String sql) {
        executeCount.incrementAndGet();
        log.info("[tool] execute_sql: {}", oneLine(sql));
        try {
            int limit = sqlExecuteService.resolveLimit(null);
            String safe = sqlExecuteService.prepareGuarded(sql, null);
            QueryResult r = sqlExecuteService.query(safe, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sql", safe);
            body.put("columns", r.columns);
            body.put("rowCount", r.rowCount);
            body.put("truncated", r.truncated);
            body.put("rows", r.rows);
            return Json.write(body);
        } catch (ApiException e) {
            return "ERROR：SQL 未通过安全校验或无法执行 —— " + e.getMessage();
        } catch (Exception e) {
            return "ERROR：SQL 执行失败 —— " + rootMessage(e)
                    + "\n请根据错误信息修正 SQL（常见原因：字段名拼写错误、缺少 GROUP BY、函数参数不合法）。";
        }
    }

    private static String oneLine(String sql) {
        if (sql == null) {
            return "null";
        }
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
