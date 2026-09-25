package com.ll.agent.text2sql;

import com.ll.agent.text2sql.config.T2SqlProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.boot.SpringBootConfiguration;

/**
 * Text2SQL Demo 启动类（Spring MVC 版，默认 8080）。
 *
 * <p>技术栈：Spring Boot 3.5 + Spring MVC + MyBatis-Plus 3.5 + MySQL 8 + AgentScope Java。
 *
 * <p>工程里还有一个 WebFlux 版对照实现（{@code webflux} 包，8081），
 * 两套 Web 栈不能同时生效，因此这里显式排除 {@code webflux} 包，
 * 由 {@link com.ll.agent.text2sql.webflux.WebFluxApplication} 自己扫描与装配
 * （这也是没有直接用 {@code @SpringBootApplication} 的原因：它不允许自定义 excludeFilters）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackageClasses = Text2SqlApplication.class)
@ConfigurationPropertiesScan("com.ll.agent.text2sql")
@ComponentScan(
        basePackages = "com.ll.agent.text2sql",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.ll\\.agent\\.text2sql\\.webflux\\..*"))
public class Text2SqlApplication {

    public static void main(String[] args) {
        System.setProperty("spring.main.web-application-type", "servlet");
        SpringApplication.run(Text2SqlApplication.class, args);
    }

    /** 启动后打印接口清单（命令行问答模式下不打印）。 */
    @Order(1)
    @Component
    static class EndpointBanner implements ApplicationRunner {

        private final T2SqlProperties properties;
        private final ApplicationContext context;

        EndpointBanner(T2SqlProperties properties, ApplicationContext context) {
            this.properties = properties;
            this.context = context;
        }

        @Override
        public void run(ApplicationArguments args) {
            String cliQuestion = properties.getCli().getQuestion();
            if (cliQuestion != null && !cliQuestion.isBlank()) {
                return;
            }
            int port = context instanceof WebServerApplicationContext web
                    ? web.getWebServer().getPort()
                    : 8080;
            String base = "http://127.0.0.1:" + port;

            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" Text2SQL Demo 已启动  (Spring MVC 版)");
            System.out.println(" 技术栈: Spring Boot 3.5 + Spring MVC + MyBatis-Plus + AgentScope"
                    + "   模型: " + properties.getLlm().getModel());
            System.out.println("----------------------------------------------------------------------");
            System.out.println(" 测试页            GET  " + base + "/");
            System.out.println(" 健康检查          GET  " + base + "/health");
            System.out.println(" 表结构            GET  " + base + "/api/schema");
            System.out.println(" 问答(全链路)      POST " + base + "/api/text2sql/ask            [JSON]");
            System.out.println(" 问答(表单)        POST " + base + "/api/text2sql/ask            [question=...]");
            System.out.println(" 问答(纯文本)      POST " + base + "/api/text2sql/ask/text       [text/plain]");
            System.out.println(" 问答(URL 参数)    GET  " + base + "/api/text2sql/ask?question=...");
            System.out.println(" 问答(SSE 流式)    GET  " + base + "/api/text2sql/ask/stream?question=...");
            System.out.println(" 只生成 SQL        POST " + base + "/api/text2sql/generate");
            System.out.println(" 直接执行 SQL      POST " + base + "/api/sql/execute");
            System.out.println(" 商品分页(MP 示例) GET  " + base + "/api/products?current=1&size=5");
            System.out.println("----------------------------------------------------------------------");
            System.out.println(" WebFlux 版对照: ./gradlew bootRunWebflux  → http://127.0.0.1:8081");
            System.out.println(" curl -s '" + base + "/api/text2sql/ask?question=各省份销售额排名前5'");
            System.out.println("======================================================================");
            System.out.println();
        }
    }
}
