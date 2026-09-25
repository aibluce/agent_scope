package com.ll.agent.text2sql.db;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.mapper.SchemaMapper;
import com.ll.agent.text2sql.model.ColumnMeta;
import com.ll.agent.text2sql.model.TableMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 表结构元数据仓库（Spring Bean）。
 *
 * <p>数据来自 {@link SchemaMapper}（information_schema），并渲染成适合喂给大模型的紧凑文本。
 * 表结构变化不频繁，默认缓存 60 秒，避免每次提问都去查元数据表。
 *
 * <p>注释信息是 Text2SQL 准确率的关键 —— 模型靠它理解 {@code order_status} 每个取值的含义。
 */
@Component
public class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    /** 表名只允许这个字符集，配合下方校验防止 ${} 拼接注入。 */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    /** 单个字段的元数据。 */
    public static final class Column {
        public final String name;
        public final String type;
        public final boolean nullable;
        public final String key;
        public final String comment;
        public final String defaultValue;

        public Column(
                String name,
                String type,
                boolean nullable,
                String key,
                String comment,
                String defaultValue) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
        }
    }

    /** 单张表的元数据。 */
    public static final class Table {
        public final String name;
        public final String comment;
        public final long rowCount;
        public final List<Column> columns;

        public Table(String name, String comment, long rowCount, List<Column> columns) {
            this.name = name;
            this.comment = comment;
            this.rowCount = rowCount;
            this.columns = columns;
        }

        public Column column(String columnName) {
            return columns.stream()
                    .filter(c -> c.name.equalsIgnoreCase(columnName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private final SchemaMapper schemaMapper;
    private final long cacheMillis;

    private volatile String schemaName;
    private volatile List<Table> cache;
    private volatile long cachedAt;

    public SchemaIntrospector(SchemaMapper schemaMapper, T2SqlProperties properties) {
        this.schemaMapper = schemaMapper;
        this.cacheMillis = Math.max(0, properties.getSchema().getCacheSeconds()) * 1000L;
    }

    /** 当前数据库名（对应 JDBC URL 里的库）。 */
    public String schemaName() {
        String name = schemaName;
        if (name == null) {
            name = schemaMapper.currentSchema();
            schemaName = name;
        }
        return name;
    }

    /** 读取（带缓存）全部表结构。 */
    public List<Table> tables() {
        List<Table> snapshot = cache;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < cacheMillis) {
            return snapshot;
        }
        synchronized (this) {
            if (cache != null && System.currentTimeMillis() - cachedAt < cacheMillis) {
                return cache;
            }
            cache = load();
            cachedAt = System.currentTimeMillis();
            return cache;
        }
    }

    /** 强制刷新缓存。 */
    public void refresh() {
        synchronized (this) {
            cache = load();
            cachedAt = System.currentTimeMillis();
        }
    }

    public Table find(String tableName) {
        if (tableName == null) {
            return null;
        }
        String name = tableName.trim().replace("`", "");
        for (Table t : tables()) {
            if (t.name.equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    public List<String> tableNames() {
        return tables().stream().map(t -> t.name).toList();
    }

    private List<Table> load() {
        String schema = schemaName();
        List<TableMeta> tableMetas = schemaMapper.selectTables(schema);
        List<ColumnMeta> columnMetas = schemaMapper.selectColumns(schema);

        Map<String, List<Column>> columnsByTable = new LinkedHashMap<>();
        for (TableMeta tm : tableMetas) {
            columnsByTable.put(tm.getTableName(), new ArrayList<>());
        }
        for (ColumnMeta cm : columnMetas) {
            columnsByTable
                    .computeIfAbsent(cm.getTableName(), k -> new ArrayList<>())
                    .add(new Column(
                            cm.getColumnName(),
                            cm.getColumnType(),
                            "YES".equalsIgnoreCase(cm.getIsNullable()),
                            nullToEmpty(cm.getColumnKey()),
                            nullToEmpty(cm.getColumnComment()),
                            nullToEmpty(cm.getColumnDefault())));
        }

        Map<String, String> comments = new LinkedHashMap<>();
        tableMetas.forEach(tm -> comments.put(tm.getTableName(), nullToEmpty(tm.getTableComment())));

        List<Table> tables = new ArrayList<>();
        columnsByTable.forEach((name, columns) -> tables.add(new Table(
                name, comments.getOrDefault(name, ""), countRows(name), columns)));
        log.debug("加载表结构完成：{} 张表", tables.size());
        return tables;
    }

    /** 精确统计行数；表名必须同时满足「字符集安全」与「确实存在于当前库」。 */
    private long countRows(String table) {
        if (!SAFE_TABLE_NAME.matcher(table).matches()) {
            return -1L;
        }
        try {
            return schemaMapper.countRows(table);
        } catch (Exception e) {
            log.warn("统计表 {} 行数失败: {}", table, e.getMessage());
            return -1L;
        }
    }

    /** 渲染全库表结构文本（用于 /api/schema 与提示词兜底）。 */
    public String renderSchema() {
        StringBuilder sb = new StringBuilder();
        sb.append("数据库 ").append(schemaName()).append(" 共 ")
                .append(tables().size()).append(" 张表：\n");
        for (Table t : tables()) {
            sb.append('\n').append(renderTable(t)).append('\n');
        }
        return sb.toString();
    }

    public String renderTable(String tableName) {
        Table t = find(tableName);
        if (t == null) {
            throw new IllegalArgumentException("表不存在: " + tableName + "，可用表: " + tableNames());
        }
        return renderTable(t);
    }

    private String renderTable(Table t) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 表 `").append(t.name).append("`");
        if (!t.comment.isEmpty()) {
            sb.append("（").append(t.comment).append("）");
        }
        if (t.rowCount >= 0) {
            sb.append("  数据量约 ").append(t.rowCount).append(" 行");
        }
        sb.append('\n');
        for (Column c : t.columns) {
            sb.append("  - `").append(c.name).append("` ").append(c.type);
            if ("PRI".equalsIgnoreCase(c.key)) {
                sb.append(" 主键");
            } else if (!c.key.isEmpty()) {
                sb.append(" ").append(c.key);
            }
            if (!c.nullable) {
                sb.append(" NOT NULL");
            }
            if (!c.comment.isEmpty()) {
                sb.append("  —— ").append(c.comment);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 按关键字模糊搜索字段（匹配字段名 / 字段注释 / 表注释）。 */
    public List<String> searchColumns(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (Table t : tables()) {
            for (Column c : t.columns) {
                if (c.name.toLowerCase(Locale.ROOT).contains(kw)
                        || c.comment.toLowerCase(Locale.ROOT).contains(kw)) {
                    hits.add(t.name + "." + c.name + " (" + c.type + ")"
                            + (c.comment.isEmpty() ? "" : "  " + c.comment));
                }
            }
            if (t.comment.toLowerCase(Locale.ROOT).contains(kw)) {
                hits.add("[表] " + t.name + " " + t.comment);
            }
        }
        return hits;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
