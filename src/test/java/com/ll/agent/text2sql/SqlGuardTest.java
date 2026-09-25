package com.ll.agent.text2sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ll.agent.text2sql.db.SqlGuard;
import com.ll.agent.text2sql.db.SqlGuard.UnsafeSqlException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SQL 安全网关的单元测试（不依赖数据库与大模型）。 */
class SqlGuardTest {

    private static final List<String> ALLOWED = List.of("t_order", "t_user", "t_province");

    @Test
    @DisplayName("从 markdown 代码块中提取 SQL")
    void extractFromCodeFence() {
        String output = """
                好的，这是最终 SQL：
                ```sql
                SELECT province_name FROM t_province LIMIT 5
                ```
                希望有帮助！""";
        assertEquals("SELECT province_name FROM t_province LIMIT 5", SqlGuard.extractSql(output));
    }

    @Test
    @DisplayName("从解释性文字中提取 SQL")
    void extractFromProse() {
        String output = "SQL: SELECT COUNT(*) FROM t_order";
        assertEquals("SELECT COUNT(*) FROM t_order", SqlGuard.extractSql(output));
    }

    @Test
    @DisplayName("去掉注释与结尾分号")
    void normalizeRemovesComments() {
        String sql = """
                -- 统计订单量
                SELECT COUNT(*) FROM t_order; /* 结束 */""";
        assertEquals("SELECT COUNT(*) FROM t_order", SqlGuard.normalize(sql));
    }

    @Test
    @DisplayName("拒绝写操作与 DDL")
    void rejectWriteStatements() {
        assertThrows(UnsafeSqlException.class, () -> SqlGuard.assertSafe("DELETE FROM t_order", ALLOWED));
        assertThrows(UnsafeSqlException.class, () -> SqlGuard.assertSafe("UPDATE t_user SET status = 0", ALLOWED));
        assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SELECT * FROM t_order; DROP TABLE t_order", ALLOWED));
        assertThrows(UnsafeSqlException.class, () -> SqlGuard.assertSafe("SELECT 1 FOR UPDATE", ALLOWED));
    }

    @Test
    @DisplayName("拒绝多条语句")
    void rejectMultipleStatements() {
        UnsafeSqlException e = assertThrows(
                UnsafeSqlException.class, () -> SqlGuard.assertSafe("SELECT 1; SELECT 2", ALLOWED));
        assertTrue(e.getMessage().contains("单条"));
    }

    @Test
    @DisplayName("拒绝白名单之外的表")
    void rejectTableOutsideWhitelist() {
        UnsafeSqlException e = assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SELECT * FROM mysql.user", ALLOWED));
        assertTrue(e.getMessage().contains("白名单") || e.getMessage().contains("系统库"));
    }

    @Test
    @DisplayName("允许常见只读查询写法")
    void allowReadOnlyQueries() {
        String sql = "SELECT p.province_name, ROUND(SUM(o.pay_amount), 2) AS amt"
                + " FROM t_order o JOIN t_province p ON o.province_id = p.id"
                + " WHERE o.order_status IN (2,3,4) GROUP BY p.province_name ORDER BY amt DESC";
        assertEquals(sql, SqlGuard.assertSafe(sql, ALLOWED));

        String withCte = "WITH t AS (SELECT id FROM t_user WHERE member_level = 4) SELECT COUNT(*) FROM t";
        assertEquals(withCte, SqlGuard.assertSafe(withCte, ALLOWED));
    }

    @Test
    @DisplayName("库白名单：当前库限定名放行，其他库拒绝")
    void schemaWhitelist() {
        List<String> schemas = List.of("ecommerce");
        assertEquals(
                "SELECT * FROM ecommerce.t_order LIMIT 1",
                SqlGuard.assertSafe("SELECT * FROM ecommerce.t_order LIMIT 1", ALLOWED, schemas));
        assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SELECT * FROM dw.t_order", ALLOWED, schemas));
        assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SELECT * FROM meta.t_user", ALLOWED, schemas));
    }

    @Test
    @DisplayName("只允许 SELECT / WITH / EXPLAIN，SHOW 与 DESC 一律拒绝")
    void rejectMetadataStatements() {
        assertThrows(UnsafeSqlException.class, () -> SqlGuard.assertSafe("SHOW TABLES", ALLOWED));
        assertThrows(UnsafeSqlException.class, () -> SqlGuard.assertSafe("DESC t_order", ALLOWED));
        assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SHOW CREATE TABLE t_order", ALLOWED));
        assertEquals(
                "SELECT * FROM t_order LIMIT 1",
                SqlGuard.assertSafe("SELECT * FROM t_order LIMIT 1", ALLOWED));
    }

    @Test
    @DisplayName("逗号连接的表也会被白名单检查")
    void commaJoinedTablesAreChecked() {
        assertThrows(
                UnsafeSqlException.class,
                () -> SqlGuard.assertSafe("SELECT * FROM t_order o, t_secret s", ALLOWED));
        assertEquals(
                "SELECT * FROM t_order o, t_user u WHERE o.user_id = u.id",
                SqlGuard.assertSafe("SELECT * FROM t_order o, t_user u WHERE o.user_id = u.id", ALLOWED));
    }

    @Test
    @DisplayName("自动补 LIMIT，并收敛过大的 LIMIT")
    void applyLimit() {
        assertEquals(
                "SELECT * FROM t_order\nLIMIT 200",
                SqlGuard.applyLimit("SELECT * FROM t_order", 200));
        assertEquals(
                "SELECT * FROM t_order LIMIT 200",
                SqlGuard.applyLimit("SELECT * FROM t_order LIMIT 10000", 200));
        assertEquals(
                "SELECT * FROM t_order LIMIT 50 , 200",
                SqlGuard.applyLimit("SELECT * FROM t_order LIMIT 50, 10000", 200));
        assertEquals(
                "SELECT * FROM t_order LIMIT 10",
                SqlGuard.applyLimit("SELECT * FROM t_order LIMIT 10", 200));
    }

    @Test
    @DisplayName("prepare 串起完整链路")
    void preparePipeline() {
        String modelOutput = "```sql\nSELECT province_name FROM t_province; -- 省份\n```";
        assertEquals(
                "SELECT province_name FROM t_province\nLIMIT 200",
                SqlGuard.prepare(modelOutput, ALLOWED, 200));
    }
}
