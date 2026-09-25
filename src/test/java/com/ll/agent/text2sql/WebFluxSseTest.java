package com.ll.agent.text2sql;

import com.ll.agent.text2sql.webflux.WebFluxApplication;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * WebFlux 版（响应式 SSE）测试：验证反应式 Web 栈能正常起、入口闸门与健康检查可用。
 *
 * <p>不调用大模型：只覆盖同步阶段就能判定的分支。
 */
@SpringBootTest(
        classes = WebFluxApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.web-application-type=reactive",
            "t2sql.guard.intent-check=false"
        })
class WebFluxSseTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("WebFlux：健康检查返回 reactive 栈信息")
    void health() {
        webTestClient.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.webStack").isEqualTo("reactive");
    }

    @Test
    @DisplayName("WebFlux：非查询指令在同步阶段直接 400（流还没建立）")
    void rejectsNonQuerySynchronously() {
        String[] notQueries = {"删除订单表所有数据", "帮我写一首诗", "查询 mysql.user 里的账号"};
        for (String question : notQueries) {
            webTestClient.post().uri("/api/text2sql/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("question", question))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.rejected").isEqualTo(true)
                    .jsonPath("$.category").exists();
        }
    }

    @Test
    @DisplayName("WebFlux：GET 方式的流式接口同样受闸门保护")
    void rejectsOnGetAsWell() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/text2sql/ask/stream")
                        .queryParam("question", "讲个笑话")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.rejected").isEqualTo(true);
    }

    @Test
    @DisplayName("WebFlux：空问题返回 400")
    void emptyQuestion() {
        webTestClient.post().uri("/api/text2sql/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
