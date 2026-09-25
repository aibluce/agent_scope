package com.ll.agent.text2sql.webflux;

import com.ll.agent.text2sql.Text2SqlApplication;
import com.ll.agent.text2sql.config.WebConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * WebFlux 版 Text2SQL（响应式 SSE 对照实现），默认端口 <b>8081</b>，与 MVC 版（8080）并存。
 *
 * <p>同一个工程里两个 Web 栈不能同时生效：classpath 上同时存在
 * {@code spring-boot-starter-web} 与 {@code spring-boot-starter-webflux} 时，
 * Spring Boot 默认判定为 SERVLET，因此这里显式 {@link WebApplicationType#REACTIVE}。
 *
 * <p>组件扫描做了三处排除，缺一不可：
 * <ol>
 *   <li>{@code controller} 包：MVC 版接口都是阻塞式写法（还有 {@code SseEmitter}），
 *       放进响应式上下文会堵住事件循环线程，且与 {@link ReactiveText2SqlController} 路由冲突；</li>
 *   <li>{@link WebConfig}：{@code WebMvcConfigurer} 在 WebFlux 下无效；</li>
 *   <li>{@link Text2SqlApplication} 及其内部类：它自身带着 {@code @ComponentScan}，
 *       若被扫进来会把上面排除掉的 MVC 控制器重新注册一遍（内部类则会让 MVC 版启动横幅再打一次）。</li>
 * </ol>
 *
 * <p>启动：{@code ./gradlew bootRunWebflux}
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackageClasses = Text2SqlApplication.class)
@ConfigurationPropertiesScan("com.ll.agent.text2sql")
@ComponentScan(
        basePackages = "com.ll.agent.text2sql",
        excludeFilters = {
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.ll\\.agent\\.text2sql\\.controller\\..*"),
            // 外层类与其内部类（EndpointBanner）都要排除，否则 MVC 版横幅会在 WebFlux 里又打一遍
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.ll\\.agent\\.text2sql\\.Text2SqlApplication.*"),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class)
        })
public class WebFluxApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WebFluxApplication.class);
        // 走响应式栈（classpath 上同时存在 starter-web 时默认会判成 SERVLET）
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        // 端口等配置放在 application-webflux.yml，IDE 里直接跑这个 main 也能生效
        application.setAdditionalProfiles("webflux");
        application.run(args);
    }

    /** 启动后打印接口清单。 */
    @Order(1)
    @Component
    static class EndpointBanner implements ApplicationRunner {

        private final ApplicationContext context;

        EndpointBanner(ApplicationContext context) {
            this.context = context;
        }

        @Override
        public void run(ApplicationArguments args) {
            int port = context instanceof WebServerApplicationContext web
                    ? web.getWebServer().getPort()
                    : 8081;
            String base = "http://127.0.0.1:" + port;
            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" Text2SQL Demo 已启动  (WebFlux 版 / 响应式 SSE)");
            System.out.println(" 与 MVC 版(8080)对照：同一条流水线，Web 层换成 Flux<ServerSentEvent>");
            System.out.println("----------------------------------------------------------------------");
            System.out.println(" SSE 流式查询     GET  " + base + "/api/text2sql/ask/stream?question=...");
            System.out.println(" SSE 流式查询     POST " + base + "/api/text2sql/ask/stream   [JSON body]");
            System.out.println(" 同步(Mono 包装)  POST " + base + "/api/text2sql/ask");
            System.out.println(" 健康检查         GET  " + base + "/health");
            System.out.println("----------------------------------------------------------------------");
            System.out.println(" curl -N '" + base + "/api/text2sql/ask/stream?question=各省份销售额排名前5'");
            System.out.println(" MVC 对照: curl -N 'http://127.0.0.1:8080/api/text2sql/ask/stream?question=...'");
            System.out.println("======================================================================");
            System.out.println();
        }
    }
}
