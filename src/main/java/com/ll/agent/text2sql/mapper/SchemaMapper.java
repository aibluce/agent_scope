package com.ll.agent.text2sql.mapper;

import com.ll.agent.text2sql.model.ColumnMeta;
import com.ll.agent.text2sql.model.TableMeta;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读取 {@code information_schema} 的 Mapper —— Text2SQL 表结构检索的元数据来源。
 *
 * <p>表名与字段注释是模型理解业务语义的主要依据，所以这里把 COMMENT 一并取出。
 */
@Mapper
public interface SchemaMapper {

    /** 当前连接的库名（对应 JDBC URL 里的数据库）。 */
    @Select("SELECT DATABASE()")
    String currentSchema();

    /** 所有基础表的表名与表注释。 */
    @Select("""
            SELECT TABLE_NAME    AS tableName,
                   TABLE_COMMENT AS tableComment
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = #{schema}
              AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """)
    List<TableMeta> selectTables(@Param("schema") String schema);

    /** 指定库下所有字段的完整定义。 */
    @Select("""
            SELECT TABLE_NAME    AS tableName,
                   COLUMN_NAME   AS columnName,
                   COLUMN_TYPE   AS columnType,
                   IS_NULLABLE   AS isNullable,
                   COLUMN_KEY    AS columnKey,
                   COLUMN_COMMENT AS columnComment,
                   COLUMN_DEFAULT AS columnDefault
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = #{schema}
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """)
    List<ColumnMeta> selectColumns(@Param("schema") String schema);

    /**
     * 精确统计表行数。
     *
     * <p>表名用 {@code ${}} 原样拼接（表名不能用占位符），因此调用前必须校验：
     * 只允许来自 information_schema 的真实表名（见 {@code SchemaIntrospector#countRows}）。
     */
    @Select("SELECT COUNT(*) FROM ${tableName}")
    long countRows(@Param("tableName") String tableName);
}
