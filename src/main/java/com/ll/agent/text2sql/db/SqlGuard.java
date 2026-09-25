package com.ll.agent.text2sql.db;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全网关：所有来自大模型的 SQL 在执行前必须经过这里。
 *
 * <p>防护能力（演示级）：
 * <ol>
 *   <li>从模型输出中提取纯 SQL（去掉 markdown 代码块、解释性文字）；</li>
 *   <li>必须是单条语句，且只允许 SELECT / WITH / SHOW / DESC / EXPLAIN 开头；</li>
 *   <li>关键字黑名单：任何写操作、DDL、权限、文件读写、锁等待、系统变量都被拒绝；</li>
 *   <li>表白名单：只允许查询配置中声明的业务表；</li>
 *   <li>自动补 LIMIT 并收敛过大的 LIMIT，避免整表扫描拖垮数据库。</li>
 * </ol>
 *
 * <p>注意：这里基于文本规则做防护，属于「纵深层」之一；真正的兜底还包括
 * JDBC 只读连接、查询超时、行数上限以及使用只读账号连接数据库（生产建议）。
 */
public final class SqlGuard {

    /** SQL 被安全网关拒绝时抛出。 */
    public static class UnsafeSqlException extends RuntimeException {
        public UnsafeSqlException(String message) {
            super(message);
        }
    }

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:sql|mysql)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(--|#)[^\\n]*");
    private static final Pattern FIRST_SQL_KEYWORD =
            Pattern.compile("(?is)\\b(select|with)\\b");

    /** 只允许这三类只读语句：SHOW / DESC 等元数据指令也会泄露库表信息，一律不放行。 */
    private static final Pattern STARTS_WITH_READ =
            Pattern.compile("(?is)^\\s*(select|with|explain)\\b");

    /** 写操作 / DDL / 权限 / 文件 / 危险函数。 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?is)\\b(insert|update|delete|drop|alter|truncate|create|replace|rename|grant|revoke"
                    + "|call|execute|prepare|deallocate|handler|lock|unlock|analyze|optimize|repair"
                    + "|load_file|load\\s+data|into\\s+outfile|into\\s+dumpfile"
                    + "|for\\s+update|lock\\s+in\\s+share\\s+mode"
                    + "|sleep|benchmark|get_lock|release_lock)\\s*\\b");

    /** 系统库。 */
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "(?is)\\b(mysql|information_schema|performance_schema|sys)\\s*(\\.|\\b)");

    /** 提取 SQL 中出现的表名（from / join 后面），支持 `库名.表名` 限定写法。 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?is)\\b(?:from|join)\\s+(?:`?([A-Za-z_][A-Za-z0-9_$]*)`?\\s*\\.\\s*)?"
                    + "`?([A-Za-z_][A-Za-z0-9_$]*)`?");

    /** 子句开头标识符，同样支持 `库名.表名`。 */
    private static final Pattern LEADING_TABLE = Pattern.compile(
            "(?is)^`?([A-Za-z_][A-Za-z0-9_$]*)`?(?:\\s*\\.\\s*`?([A-Za-z_][A-Za-z0-9_$]*)`?)?");

    /** FROM 子句（用于识别 FROM a, b 这种逗号连接的表）。 */
    private static final Pattern FROM_CLAUSE = Pattern.compile(
            "(?is)\\bfrom\\b\\s+(.*?)(?=\\bwhere\\b|\\bgroup\\s+by\\b|\\bhaving\\b|"
                    + "\\border\\s+by\\b|\\blimit\\b|\\bunion\\b|\\)|$)");

    /** CTE 名称：WITH x AS ( ... ), y AS ( ... )，它们不是真实表，需要放行。 */
    private static final Pattern CTE_NAME =
            Pattern.compile("(?is)(?:\\bwith\\b|,)\\s*`?([A-Za-z_][A-Za-z0-9_$]*)`?\\s+as\\s*\\(");

    private static final Pattern LIMIT_CLAUSE =
            Pattern.compile("(?is)\\blimit\\s+(\\d+)(\\s*,\\s*(\\d+))?");

    private SqlGuard() {}

    /**
     * 从大模型的输出中提取一条 SQL。
     *
     * <p>优先取 markdown 代码块，其次取第一个 SELECT/WITH 开始的内容。
     */
    public static String extractSql(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new UnsafeSqlException("模型没有返回任何内容");
        }
        String text = modelOutput.trim();
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        } else if (!STARTS_WITH_READ.matcher(text).find()) {
            Matcher first = FIRST_SQL_KEYWORD.matcher(text);
            if (first.find()) {
                text = text.substring(first.start()).trim();
            }
        }
        return text;
    }

    /** 去掉注释、结尾分号，得到单条语句。 */
    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("SQL 为空");
        }
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = s.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.contains(";")) {
            throw new UnsafeSqlException("只允许执行单条 SQL 语句，检测到多条语句（分号）");
        }
        return s;
    }

    /**
     * 安全校验。
     *
     * @param sql           已经 normalize 过的 SQL
     * @param allowedTables 表白名单，空列表表示不限制
     * @return 原 SQL（便于链式调用）
     */
    public static String assertSafe(String sql, List<String> allowedTables) {
        return assertSafe(sql, allowedTables, List.of());
    }

    /**
     * 安全校验。
     *
     * @param sql            已经 normalize 过的 SQL
     * @param allowedTables  可查询的表白名单，空列表表示不限制
     * @param allowedSchemas 可访问的库白名单（空表示不校验库名，但仍会拦截系统库）
     * @return 原 SQL（便于链式调用）
     */
    public static String assertSafe(
            String sql, List<String> allowedTables, List<String> allowedSchemas) {
        String stripped = normalize(sql);

        if (!STARTS_WITH_READ.matcher(stripped).find()) {
            throw new UnsafeSqlException("只允许 SELECT / WITH / EXPLAIN 开头的只读查询"
                    + "（不支持 SHOW / DESC 等元数据指令，也不支持任何写入或 DDL），实际为: "
                    + preview(stripped));
        }
        Matcher forbidden = FORBIDDEN.matcher(stripped);
        if (forbidden.find()) {
            throw new UnsafeSqlException("SQL 中包含被禁止的关键字 `" + forbidden.group(1).trim()
                    + "`，本接口只允许只读查询");
        }
        Matcher sysSchema = SYSTEM_SCHEMA.matcher(stripped);
        if (sysSchema.find()) {
            throw new UnsafeSqlException("禁止访问系统库 `" + sysSchema.group(1) + "`");
        }

        List<TableRef> refs = extractTableRefs(stripped);

        // ---- 库名校验：非白名单库（如 dw.other_table）一律拒绝，系统库另行拦截 ----
        if (allowedSchemas != null && !allowedSchemas.isEmpty()) {
            Set<String> allowSchemas = new LinkedHashSet<>();
            allowedSchemas.forEach(s2 -> allowSchemas.add(s2.toLowerCase(Locale.ROOT)));
            List<String> illegalSchemas = new ArrayList<>();
            for (TableRef ref : refs) {
                if (ref.schema() != null && !allowSchemas.contains(ref.schema())) {
                    illegalSchemas.add(ref.schema());
                }
            }
            if (!illegalSchemas.isEmpty()) {
                throw new UnsafeSqlException("SQL 访问了可查询范围之外的库 " + illegalSchemas
                        + "，本接口只允许访问当前库: " + allowedSchemas);
            }
        }

        // ---- 表名校验 ----
        if (allowedTables != null && !allowedTables.isEmpty()) {
            Set<String> allow = new LinkedHashSet<>();
            allowedTables.forEach(t -> allow.add(t.toLowerCase(Locale.ROOT)));
            // CTE 名（WITH x AS (...)）会被 from 引用，但不是真实表，需要一起放行
            Matcher cte = CTE_NAME.matcher(stripped);
            while (cte.find()) {
                allow.add(cte.group(1).toLowerCase(Locale.ROOT));
            }
            List<String> illegal = new ArrayList<>();
            for (TableRef ref : refs) {
                if (!allow.contains(ref.table())) {
                    illegal.add(ref.table());
                }
            }
            if (!illegal.isEmpty()) {
                throw new UnsafeSqlException("SQL 引用了可查询范围之外的表 " + illegal
                        + "，本接口只允许查询: " + allowedTables);
            }
        }
        return stripped;
    }

    /** 一条 SQL 里引用到的一张表（可能带库名前缀）。 */
    private record TableRef(String schema, String table) {}

    /** 提取 SQL 中引用的所有表：FROM/JOIN 后面的、以及 FROM a, b 逗号连接里的（含 库名.表名 写法）。 */
    private static List<TableRef> extractTableRefs(String sql) {
        List<TableRef> refs = new ArrayList<>();
        Matcher ref = TABLE_REF.matcher(sql);
        while (ref.find()) {
            refs.add(toRef(ref.group(1), ref.group(2)));
        }
        Matcher fromClause = FROM_CLAUSE.matcher(sql);
        while (fromClause.find()) {
            for (String part : fromClause.group(1).split(",")) {
                Matcher leading = LEADING_TABLE.matcher(part.trim());
                if (leading.find()) {
                    String first = leading.group(1);
                    String second = leading.group(2);
                    refs.add(second == null ? toRef(null, first) : toRef(first, second));
                }
            }
        }
        return refs;
    }

    private static TableRef toRef(String schema, String table) {
        return new TableRef(
                schema == null ? null : schema.toLowerCase(Locale.ROOT),
                table.toLowerCase(Locale.ROOT));
    }

    /** 没有 LIMIT 时自动补上，LIMIT 过大时收敛到上限（仅对 SELECT / WITH 生效）。 */
    public static String applyLimit(String sql, int maxRows) {
        if (maxRows <= 0 || !Pattern.compile("(?is)^\\s*(select|with)\\b").matcher(sql).find()) {
            return sql;
        }
        Matcher m = LIMIT_CLAUSE.matcher(sql);
        if (m.find()) {
            String offsetPart = m.group(2);
            String count = m.group(3) != null ? m.group(3) : m.group(1);
            if (Integer.parseInt(count) <= maxRows) {
                return sql;
            }
            String replacement = offsetPart != null
                    ? "LIMIT " + m.group(1) + " , " + maxRows
                    : "LIMIT " + maxRows;
            return sql.substring(0, m.start()) + replacement + sql.substring(m.end());
        }
        return sql + "\nLIMIT " + maxRows;
    }

    /**
     * 一站式处理模型输出：提取 → 规范化 → 安全校验 → 加 LIMIT。
     *
     * @return 可以安全执行的 SQL
     */
    public static String prepare(String rawModelOrUserSql, List<String> allowedTables, int maxRows) {
        return prepare(rawModelOrUserSql, allowedTables, List.of(), maxRows);
    }

    /** 一站式处理：提取 → 规范化 → 安全校验（表 + 库白名单）→ 加 LIMIT。 */
    public static String prepare(
            String rawModelOrUserSql,
            List<String> allowedTables,
            List<String> allowedSchemas,
            int maxRows) {
        String sql = normalize(extractSql(rawModelOrUserSql));
        assertSafe(sql, allowedTables, allowedSchemas);
        return applyLimit(sql, maxRows);
    }

    private static String preview(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }
}
