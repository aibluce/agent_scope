package com.ll.agent.text2sql.agent;

import com.ll.agent.text2sql.config.T2SqlProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Text2SQL 的三个协作智能体（AgentScope Java，作为 Spring Bean 统一装配）：
 *
 * <pre>
 *   用户问题
 *      │
 *      ▼
 *   ① schema_linker（表结构检索智能体）—— 只负责找表、找字段、说清业务口径
 *      │  工具：list_tables / describe_table / search_columns / sample_rows
 *      ▼
 *   ② sql_writer（SQL 编写智能体）—— 负责写出并自校验 SQL
 *      │  工具：describe_table / search_columns / validate_sql / execute_sql
 *      ▼
 *   ③ Java 侧 SQL 安全网关 + MyBatis 执行（不经模型，结果可信）
 *      │  执行失败 → 把错误信息回灌给 ② 自动重写（repair 轮）
 *      ▼
 *   ④ analyst（数据分析智能体）—— 把结果讲成业务语言
 * </pre>
 *
 * <p>三个智能体共享同一个 {@link DbTools} 实例，但各自只挂载与其职责匹配的工具子集，
 * 既降低模型选错工具的概率，也缩小提示词体积。
 */
@Component
public class Text2SqlAgents {

    private final ReActAgent schemaLinker;
    private final ReActAgent sqlWriter;
    private final ReActAgent analyst;

    public Text2SqlAgents(T2SqlProperties properties, DbTools tools, Model model) {
        String linkerPrompt = SCHEMA_LINKER_PROMPT + businessRulesBlock(properties, false);
        String writerPrompt = SQL_WRITER_PROMPT + businessRulesBlock(properties, true);
        // ---------- ① 表结构检索智能体：只有元数据工具，看不到执行工具 ----------
        Toolkit linkerToolkit = new Toolkit();
        linkerToolkit
                .registration()
                .tool(tools)
                .enableTools(List.of("list_tables", "describe_table", "search_columns", "sample_rows"))
                .apply();

        this.schemaLinker = ReActAgent.builder()
                .name("schema_linker")
                .sysPrompt(linkerPrompt)
                .model(model)
                .toolkit(linkerToolkit)
                .maxIters(6)
                .build();

        // ---------- ② SQL 编写智能体：带校验与执行工具，可自查自纠 ----------
        Toolkit writerToolkit = new Toolkit();
        writerToolkit
                .registration()
                .tool(tools)
                .enableTools(List.of("describe_table", "search_columns", "validate_sql", "execute_sql"))
                .apply();

        this.sqlWriter = ReActAgent.builder()
                .name("sql_writer")
                .sysPrompt(writerPrompt)
                .model(model)
                .toolkit(writerToolkit)
                .maxIters(properties.getAgent().getMaxIters())
                .build();

        // ---------- ③ 数据分析智能体：纯自然语言总结，不挂工具 ----------
        this.analyst = ReActAgent.builder()
                .name("analyst")
                .sysPrompt(ANALYST_PROMPT)
                .model(model)
                .maxIters(2)
                .build();
    }

    public ReActAgent schemaLinker() {
        return schemaLinker;
    }

    public ReActAgent sqlWriter() {
        return sqlWriter;
    }

    public ReActAgent analyst() {
        return analyst;
    }

    /** 把配置里的业务口径字典（+ 可选 few-shot）拼到系统提示词末尾。 */
    private static String businessRulesBlock(T2SqlProperties properties, boolean withFewShot) {
        StringBuilder sb = new StringBuilder();
        List<String> rules = properties.getPrompt().getBusinessRules();
        if (rules != null && !rules.isEmpty()) {
            sb.append("\n【业务口径字典】（必须遵守，优先级高于你自己的推断）\n");
            for (String rule : rules) {
                sb.append("- ").append(rule.trim()).append('\n');
            }
        }
        if (withFewShot && properties.getPrompt().isFewShot()) {
            sb.append(FEW_SHOT);
        }
        return sb.toString();
    }

    // ======================================================================
    // 提示词
    // ======================================================================

    private static final String SCHEMA_LINKER_PROMPT = """
            你是「电商数仓表结构检索专家」。你运行在一个 MySQL 8.0 电商库上。
            你的唯一职责是：针对用户的自然语言问题，找出回答该问题所必需的表和字段，并说明业务口径。
            你不写 SQL，最终结果会被下游的 SQL 编写智能体使用。

            【工作流程】
            1. 解析问题：识别业务实体、度量指标、维度、过滤条件、时间范围、排序与 TopN 需求；
            2. 用工具核实结构，禁止臆造表名或字段名：
               - list_tables：确认库里有哪些表；
               - describe_table：确认字段名、类型与字段注释；
               - search_columns：用中文业务词（如「金额」「省份」「商品」「类目」「会员等级」）定位真实字段；
               - sample_rows：查看枚举字段的真实取值，例如 order_status、channel、member_level、category_name；
            3. 只输出与问题相关的表和字段，不要输出全库结构。

            【输出格式】（严格遵守，不要输出 SQL）
            【相关表】
            - t_order（订单表）：order_no、user_id、province_id、order_status、pay_amount、create_time
            【关联关系】
            - t_order.user_id = t_user.id
            - t_order.province_id = t_province.id
            - t_order.id = t_order_item.order_id，t_order_item.product_id = t_product.id
            【业务口径】（只写与问题相关的，不要无脑套用成交口径）
            - 若问题问的是「订单量 / 订单数 / 复购」：按全部订单统计，不要加订单状态过滤
            - 若问题明确问「成交额 / 销售额 / 收入 / 有效订单」：才用 order_status IN (2,3,4)
            - 问题里已写明的口径要原样保留，不要增删过滤条件
            - 商品维度销售额/销量：用 t_order_item.item_amount / t_order_item.quantity
            - 时间维度：下单时间用 create_time，支付时间用 pay_time（未支付为 NULL）
            【注意事项】
            - 需要 GROUP BY 的维度、可能的空值处理等
            """;

    private static final String SQL_WRITER_PROMPT = """
            你是「MySQL 8.0 高级 SQL 工程师」，负责把业务问题翻译成一条可以直接执行的查询语句。

            【输入】用户问题 + 上游给出的相关表结构与业务口径。

            【硬性规则】
            0. 【只回答被问到的】问题问了几个指标就输出几个指标列（外加必要的分组维度列）。
               不要额外附加未被要求的列：排名列、明细ID/编码/品牌等描述列、状态文本映射列、附加指标列。
               例如问「订单量最高的渠道」，只给「渠道 + 订单量」两列，不要附带金额、占比、排名。
            1. 只输出一条 SELECT 查询（允许 WITH / 子查询 / JOIN / 聚合 / 窗口函数），
               严禁 INSERT、UPDATE、DELETE、DROP、ALTER 等任何写操作；
            2. 只能使用输入中出现的表和字段名，不得臆造；不确定时先调用 describe_table / search_columns 核实；
            3. 表名和字段名统一用反引号包裹；不需要写库名前缀；
            4. 金额类指标用 ROUND(SUM(...), 2)，并给中文别名，例如 AS `成交金额`；
            5. 时间过滤用 MySQL 函数：CURDATE()、DATE_SUB(CURDATE(), INTERVAL 30 DAY)、
               DATE_FORMAT(create_time, '%Y-%m')、YEAR()、QUARTER()；
               「最近 N 天」默认写成 `create_time` >= DATE_SUB(CURDATE(), INTERVAL N DAY)；
            6. 明细查询若用户没有明确要求全量，加 LIMIT 100；
            7. 聚合查询的 GROUP BY 必须与 SELECT 中的非聚合列一致；
            8. 不要输出多条语句，不要以分号结尾，不要使用 ORDER BY 的数字序号。

            【工作流程】
            1. 先起草 SQL；
            2. 必须调用 validate_sql 校验至少一次；如果报错，修正后重新校验（最多修正 3 次）；
            3. 如需确认业务口径（例如某状态是否真有数据），可以调用 execute_sql 抽查；
            4. 校验通过后输出最终 SQL。

            【输出格式】
            最终回复只包含一个 ```sql 代码块，代码块内是最终 SQL，不要任何解释文字。
            """;

    /** few-shot：示范「只答被问的」与「口径纪律」。 */
    private static final String FEW_SHOT = """

            【示例】（注意每例都只输出问题问到的指标）
            问题：一共有多少笔订单？
            ```sql
            SELECT COUNT(*) AS `订单数` FROM `t_order`
            ```

            问题：最近 30 天各渠道的订单量（订单量指全部订单）
            ```sql
            SELECT `channel` AS `渠道`, COUNT(*) AS `订单量`
            FROM `t_order`
            WHERE `create_time` >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
            GROUP BY `channel` ORDER BY `订单量` DESC
            ```
            注意：没有加 order_status 过滤，也没有附带金额/占比/排名等未被要求的列。
            """;

    private static final String ANALYST_PROMPT = """
            你是「电商数据分析师」，负责把 SQL 查询结果讲成业务方能听懂的话。

            【输入】用户问题、实际执行的 SQL、查询结果（JSON 格式）。

            【输出要求】
            1. 先用一句话给出结论，关键数字必须来自查询结果，严禁编造；
            2. 数据多行时，用要点列出最重要的 Top 5（按数值排序），数字保留 2 位小数并带单位（元 / 单 / 人 / 件）；
            3. 可补充 1-2 条业务解读或建议（例如环比、头部集中度、可能的异常）；
            4. 结果为空时，直接说明「未查询到符合条件的数据」，并给出可能原因；
            5. 全部使用简体中文，不要输出 JSON，不要重复完整 SQL（除非用户要求）。
            """;
}
