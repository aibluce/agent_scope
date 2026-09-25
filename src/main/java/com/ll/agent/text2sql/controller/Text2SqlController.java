package com.ll.agent.text2sql.controller;

import com.ll.agent.text2sql.model.Text2SqlRequest;
import com.ll.agent.text2sql.model.Text2SqlResult;
import com.ll.agent.text2sql.service.SqlExecuteService;
import com.ll.agent.text2sql.service.Text2SqlService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Text2SQL 主接口（Spring MVC）。
 *
 * <p>请求内容就是「用户输入的自然语言」，同一个能力支持四种提交方式：
 * <pre>
 *   POST /api/text2sql/ask          {"question":"各省份销售额排名前5"}   （JSON）
 *   POST /api/text2sql/ask          question=各省份销售额排名前5         （表单 form-urlencoded）
 *   POST /api/text2sql/ask/text     各省份销售额排名前5                  （纯文本）
 *   GET  /api/text2sql/ask?question=各省份销售额排名前5                  （URL 参数，浏览器可直接打开）
 * </pre>
 */
@RestController
@RequestMapping("/api/text2sql")
public class Text2SqlController {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlController.class);

    private final Text2SqlService text2SqlService;
    private final SqlExecuteService sqlExecuteService;

    public Text2SqlController(Text2SqlService text2SqlService, SqlExecuteService sqlExecuteService) {
        this.text2SqlService = text2SqlService;
        this.sqlExecuteService = sqlExecuteService;
    }

    // ------------------------------------------------------------------ 全链路：问题 -> SQL -> 数据 -> 结论

    /** JSON 方式：用户输入放在请求体里。 */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Text2SqlResult askJson(@Valid @RequestBody Text2SqlRequest request) {
        log.info("收到问题(JSON): {}", request.resolvedQuestion());
        return text2SqlService.ask(request);
    }

    /** 表单方式：question / sessionId / includeAnswer / maxRows 作为表单字段。 */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Text2SqlResult askForm(@Valid Text2SqlRequest request) {
        log.info("收到问题(表单): {}", request.resolvedQuestion());
        return text2SqlService.ask(request);
    }

    /** 纯文本方式：请求体就是用户输入的原话。 */
    @PostMapping(value = "/ask/text", consumes = MediaType.TEXT_PLAIN_VALUE)
    public Text2SqlResult askText(@RequestBody String question) {
        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        log.info("收到问题(纯文本): {}", request.resolvedQuestion());
        return text2SqlService.ask(request);
    }

    /** URL 参数方式：浏览器里直接打开即可（便于快速验证）。 */
    @GetMapping("/ask")
    public Text2SqlResult askGet(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "includeAnswer", required = false) Boolean includeAnswer,
            @RequestParam(value = "maxRows", required = false) Integer maxRows) {
        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        request.setSessionId(sessionId);
        request.setIncludeAnswer(includeAnswer);
        request.setMaxRows(maxRows);
        log.info("收到问题(GET): {}", request.resolvedQuestion());
        return text2SqlService.ask(request);
    }

    // ------------------------------------------------------------------ 只生成 SQL

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Text2SqlResult generateJson(@Valid @RequestBody Text2SqlRequest request) {
        log.info("收到问题(只生成 SQL): {}", request.resolvedQuestion());
        return text2SqlService.generate(request);
    }

    @GetMapping("/generate")
    public Text2SqlResult generateGet(
            @RequestParam("question") String question,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        Text2SqlRequest request = new Text2SqlRequest();
        request.setQuestion(question);
        request.setSessionId(sessionId);
        return text2SqlService.generate(request);
    }

    /** 当前生效的运行时配置（便于排查某条 SQL 为什么被拦截）。 */
    @GetMapping("/limits")
    public Map<String, Object> limits() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", sqlExecuteService.properties().getLlm().getModel());
        body.put("maxRows", sqlExecuteService.properties().getSql().getMaxRows());
        body.put("allowedTables", sqlExecuteService.properties().getSql().getAllowedTables());
        body.put("repairRounds", sqlExecuteService.properties().getAgent().getRepairRounds());
        return body;
    }
}
