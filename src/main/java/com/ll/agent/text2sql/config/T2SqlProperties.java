package com.ll.agent.text2sql.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Text2SQL Demo 的业务配置，对应 application.yml 中的 {@code t2sql.*}。
 *
 * <p>Spring Boot 的宽松绑定让环境变量可以直接覆盖，例如：
 * {@code t2sql.llm.api-key} → {@code T2SQL_LLM_API_KEY}，
 * {@code t2sql.sql.max-rows} → {@code T2SQL_SQL_MAX_ROWS}。
 */
@Data
@ConfigurationProperties(prefix = "t2sql")
public class T2SqlProperties {

    private Llm llm = new Llm();
    private Sql sql = new Sql();
    private Agent agent = new Agent();
    private Schema schema = new Schema();
    private Guard guard = new Guard();
    private Prompt prompt = new Prompt();
    private Cli cli = new Cli();

    /** 大模型配置（任何兼容 OpenAI 协议的服务都可用）。 */
    @Data
    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-flash";
        private String apiKey = "";

        /** 取有效 Key：yml > 环境变量。 */
        public String effectiveApiKey() {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.trim();
            }
            for (String env : new String[] {"T2SQL_LLM_API_KEY", "DEEPSEEK_API_KEY", "OPENAI_API_KEY"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
            return "";
        }
    }

    /** SQL 安全相关配置。 */
    @Data
    public static class Sql {
        /** 单次查询返回行数上限。 */
        private int maxRows = 200;
        /** 允许被查询的表白名单，空表示不限制。 */
        private List<String> allowedTables = new ArrayList<>();
    }

    /** 智能体配置。 */
    @Data
    public static class Agent {
        private int maxIters = 6;
        private int timeoutSeconds = 120;
        /** SQL 执行失败后自动纠错轮数。 */
        private int repairRounds = 1;
        /** 结果为空时是否让智能体放宽条件重试。 */
        private boolean retryOnEmpty = true;
    }

    /** 表结构元数据缓存。 */
    @Data
    public static class Schema {
        private int cacheSeconds = 60;
    }

    /**
     * 入口意图闸门：只允许「查询电商业务数据」的问题，其他指令直接提示不支持。
     */
    @Data
    public static class Guard {
        /** 是否开启入口校验（关闭后仅保留 SQL 层防护）。 */
        private boolean enabled = true;
        /** 是否再用大模型复核一次意图（更准，但每次多一次模型调用）。 */
        private boolean intentCheck = true;
        /** 用户输入的最大长度，超长直接拒绝。 */
        private int maxQuestionLength = 500;
    }

    /**
     * 提示词增强：业务口径字典（相当于轻量语义层）。
     *
     * <p>这些规则会拼进「表结构检索」与「SQL 编写」两个智能体的系统提示词，
     * 用来抑制两类高频错误：口径自加戏（乱加 order_status 过滤）与过度输出（多给列）。
     */
    @Data
    public static class Prompt {
        /** 业务口径规则，逐条拼成 "- xxx" 列表。 */
        private List<String> businessRules = new ArrayList<>();
        /** 是否追加 few-shot 示例。 */
        private boolean fewShot = true;
    }

    /** 命令行模式：question 非空时跑一次问答就退出。 */
    @Data
    public static class Cli {
        private String question = "";
    }
}
