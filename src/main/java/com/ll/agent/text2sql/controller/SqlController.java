package com.ll.agent.text2sql.controller;

import com.ll.agent.text2sql.db.SchemaIntrospector;
import com.ll.agent.text2sql.model.SqlExecution;
import com.ll.agent.text2sql.model.SqlRequest;
import com.ll.agent.text2sql.service.Text2SqlService;
import java.util.LinkedHashMap;
import java.util.List;
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
 * SQL 执行与表结构接口。
 *
 * <pre>
 *   POST /api/sql/execute     {"sql":"SELECT ..."}   执行一条只读 SQL（过安全网关）
 *   GET  /api/sql/execute?sql=SELECT ...             同上，便于浏览器/curl 直接测
 *   GET  /api/schema                                 当前库全部表结构与注释
 *   POST /api/schema/refresh                         刷新表结构缓存
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class SqlController {

    private static final Logger log = LoggerFactory.getLogger(SqlController.class);

    private final Text2SqlService text2SqlService;

    public SqlController(Text2SqlService text2SqlService) {
        this.text2SqlService = text2SqlService;
    }

    @PostMapping(value = "/sql/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SqlExecution executeJson(@RequestBody SqlRequest body) {
        log.info("执行 SQL(JSON): {}", body == null ? null : body.getSql());
        return text2SqlService.execute(body == null ? null : body.getSql(),
                body == null ? null : body.getMaxRows());
    }

    @GetMapping("/sql/execute")
    public SqlExecution executeGet(
            @RequestParam("sql") String sql,
            @RequestParam(value = "maxRows", required = false) Integer maxRows) {
        log.info("执行 SQL(GET): {}", sql);
        return text2SqlService.execute(sql, maxRows);
    }

    /** 当前库的表结构（字段、类型、注释）与渲染后的文本。 */
    @GetMapping("/schema")
    public Map<String, Object> schema() {
        SchemaIntrospector schema = text2SqlService.schemaIntrospector();
        schema.refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("database", schema.schemaName());
        body.put("tables", schema.tables());
        body.put("text", schema.renderSchema());
        return body;
    }

    @PostMapping("/schema/refresh")
    public Map<String, Object> refreshSchema() {
        SchemaIntrospector schema = text2SqlService.schemaIntrospector();
        schema.refresh();
        return Map.of("success", true, "tables", schema.tableNames());
    }

    /** 单纯列出表清单（轻量接口）。 */
    @GetMapping("/tables")
    public List<String> tables() {
        return text2SqlService.schemaIntrospector().tableNames();
    }
}
