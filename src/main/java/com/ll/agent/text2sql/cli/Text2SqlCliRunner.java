package com.ll.agent.text2sql.cli;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.model.Text2SqlRequest;
import com.ll.agent.text2sql.model.Text2SqlResult;
import com.ll.agent.text2sql.service.Text2SqlService;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 命令行模式：{@code --t2sql.cli.question="各省份销售额排名前5"} 时跑一次问答并退出，
 * 不传该参数则正常启动 Web 服务。
 *
 * <pre>
 *   ./gradlew askText2Sql -Pquestion="销量最高的 5 款商品"
 * </pre>
 */
@Order(2)
@Component
public class Text2SqlCliRunner implements ApplicationRunner {

    private final T2SqlProperties properties;
    private final Text2SqlService text2SqlService;
    private final ConfigurableApplicationContext context;

    public Text2SqlCliRunner(
            T2SqlProperties properties,
            Text2SqlService text2SqlService,
            ConfigurableApplicationContext context) {
        this.properties = properties;
        this.text2SqlService = text2SqlService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String question = properties.getCli().getQuestion();
        if (question == null || question.isBlank()) {
            return;
        }

        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        request.setIncludeAnswer(true);

        long start = System.currentTimeMillis();
        Text2SqlResult result = text2SqlService.ask(request);

        System.out.println();
        System.out.println("────────────────────────────────────────────────────────");
        System.out.println("问题: " + result.question);
        System.out.println("────────────────────────────────────────────────────────");
        System.out.println("SQL:");
        System.out.println(result.sql);
        System.out.println("────────────────────────────────────────────────────────");
        System.out.println("结果 (" + result.rowCount + " 行" + (result.truncated ? "，已截断" : "") + "):");
        printTable(result);
        System.out.println("────────────────────────────────────────────────────────");
        System.out.println("结论:");
        System.out.println(result.answer);
        System.out.println("────────────────────────────────────────────────────────");
        System.out.println("链路耗时:");
        result.steps.forEach(s -> System.out.printf("  %6d ms  %s%n", s.elapsedMs, s.stage));
        System.out.println("总耗时: " + (System.currentTimeMillis() - start) + " ms");
        System.out.println();

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void printTable(Text2SqlResult result) {
        if (result.rows == null || result.rows.isEmpty()) {
            System.out.println("  (无数据)");
            return;
        }
        StringBuilder header = new StringBuilder("  ");
        result.columns.forEach(c -> header.append(pad(c)).append(" "));
        System.out.println(header);
        for (Map<String, Object> row : result.rows) {
            StringBuilder line = new StringBuilder("  ");
            for (String c : result.columns) {
                Object v = row.get(c);
                line.append(pad(v == null ? "NULL" : String.valueOf(v))).append(" ");
            }
            System.out.println(line);
        }
    }

    private static String pad(String value) {
        String v = value.length() > 24 ? value.substring(0, 21) + "..." : value;
        return String.format("%-24s", v);
    }
}
