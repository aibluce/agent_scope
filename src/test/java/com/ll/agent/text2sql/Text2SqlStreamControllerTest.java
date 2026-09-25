package com.ll.agent.text2sql;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ll.agent.text2sql.db.QueryResult;
import com.ll.agent.text2sql.guard.InputGuard;
import com.ll.agent.text2sql.model.Text2SqlResult;
import com.ll.agent.text2sql.service.PipelineListener;
import com.ll.agent.text2sql.service.Text2SqlService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SSE 流式接口测试。
 *
 * <p>{@link Text2SqlService} 用 Mockito 替换成"会回调监听器"的假实现，
 * 因此这里验证的是 SSE 事件链路本身（事件名、顺序、data 结构），不调用大模型。
 */
@SpringBootTest(properties = "t2sql.guard.intent-check=false")
@AutoConfigureMockMvc
class Text2SqlStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Text2SqlService text2SqlService;

    @Test
    @DisplayName("SSE：按 meta -> stage -> tool -> sql -> rows -> done 顺序推送事件")
    void streamsPipelineEvents() throws Exception {
        when(text2SqlService.askChecked(any(), any())).thenAnswer(invocation -> {
            PipelineListener listener = invocation.getArgument(1);
            listener.onStart("req-test", "sess-test", "各商品类目的销售额排名");
            listener.onStageStart("① 表结构检索智能体 schema_linker");
            listener.onToolCall("① 表结构检索智能体 schema_linker", "list_tables");
            listener.onDelta("① 表结构检索智能体 schema_linker", "正在检索");
            listener.onStageEnd("① 表结构检索智能体 schema_linker", 120L, "检索完成");
            listener.onStageStart("② SQL 生成智能体 sql_writer");
            listener.onStageEnd("② SQL 生成智能体 sql_writer", 80L, "```sql ...```");
            listener.onSqlReady("SELECT 1 AS `数量`\nLIMIT 200");
            listener.onRows(new QueryResult(
                    List.of("数量"), List.of(Map.of("数量", 1)), false, 5L));
            Text2SqlResult result = Text2SqlResult.of(
                    "req-test", "sess-test", "各商品类目的销售额排名", List.of(), 200L);
            result.sql = "SELECT 1 AS `数量`\nLIMIT 200";
            result.answer = "结论：共 1 条";
            result.rowCount = 1;
            result.columns = List.of("数量");
            result.rows = List.of(Map.of("数量", 1));
            listener.onDone(result);
            return result;
        });

        MvcResult mvcResult = mockMvc.perform(get("/api/text2sql/ask/stream")
                        .param("question", "各商品类目的销售额排名"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String body = mvcResult.getResponse().getContentAsString();
        assertTrue(body.contains("event:meta"), body);
        assertTrue(body.contains("event:stage"), body);
        assertTrue(body.contains("event:tool"), body);
        assertTrue(body.contains("event:delta"), body);
        assertTrue(body.contains("event:sql"), body);
        assertTrue(body.contains("event:rows"), body);
        assertTrue(body.contains("event:done"), body);
        assertTrue(body.contains("req-test"), body);
        assertTrue(body.contains("结论：共 1 条"), body);
        // 事件顺序：meta 必须在 sql 之前，sql 在 done 之前
        assertTrue(body.indexOf("event:meta") < body.indexOf("event:sql"), body);
        assertTrue(body.indexOf("event:sql") < body.indexOf("event:done"), body);
    }

    @Test
    @DisplayName("SSE：非查询指令在同步阶段直接返回 400，不进入流式流程")
    void rejectsNonQuerySynchronously() throws Exception {
        String[] notQueries = {"删除订单表所有数据", "帮我写一首诗", "查询 mysql.user 里的账号"};
        for (String question : notQueries) {
            mockMvc.perform(post("/api/text2sql/ask/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + question + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.rejected").value(true))
                    .andExpect(jsonPath("$.category").exists())
                    .andExpect(jsonPath("$.examples").isArray());
        }
    }

    @Test
    @DisplayName("SSE：拒绝提示里的示例来自 InputGuard")
    void rejectionExamplesComeFromGuard() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/text2sql/ask/stream").param("question", "讲个笑话"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains(InputGuard.EXAMPLES.get(0)), body);
    }
}
