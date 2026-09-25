package com.ll.agent.text2sql.agent;

import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流水线提示词模板（MVC 版与 WebFlux 版共用，避免两套实现漂移）。
 */
public final class Prompts {

    /** 喂给分析智能体的最大行数，避免提示词过长。 */
    private static final int MAX_ROWS_TO_ANALYST = 50;

    private Prompts() {}

    /** ① 表结构检索阶段的输入。 */
    public static String schemaLink(String question) {
        return "用户问题：" + question + "\n请检索回答该问题所需的表、字段与业务口径。";
    }

    /** ② SQL 编写阶段的输入（带上一轮的错误信息时即为纠错重写）。 */
    public static String writer(String question, String schemaContext, String previousSql, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户问题】\n").append(question).append("\n\n");
        sb.append("【可用的表结构与业务口径】\n").append(schemaContext).append("\n\n");
        if (previousSql != null && error != null) {
            sb.append("【上一次的 SQL（有问题，请修正）】\n").append(previousSql).append("\n\n");
        }
        if (error != null) {
            sb.append("【上一次失败原因】\n").append(error).append("\n\n");
            sb.append("请修正后重新生成 SQL，并调用 validate_sql 确认可以执行，最后只输出 ```sql 代码块。\n");
        } else {
            sb.append("请生成 SQL，先调用 validate_sql 校验，最后只输出 ```sql 代码块。\n");
        }
        return sb.toString();
    }

    /** ③ 结果解读阶段的输入。 */
    public static String analyst(String question, String sql, QueryResult qr) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("columns", qr.columns);
        payload.put("rowCount", qr.rowCount);
        payload.put("truncated", qr.truncated);
        payload.put("rows", qr.rows.size() > MAX_ROWS_TO_ANALYST
                ? qr.rows.subList(0, MAX_ROWS_TO_ANALYST)
                : qr.rows);

        return "【用户问题】\n" + question + "\n\n"
                + "【实际执行的 SQL】\n" + sql + "\n\n"
                + "【查询结果】\n" + Json.pretty(payload) + "\n\n"
                + "请用简体中文给出结论与解读。";
    }
}
