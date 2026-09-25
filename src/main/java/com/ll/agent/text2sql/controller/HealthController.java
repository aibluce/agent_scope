package com.ll.agent.text2sql.controller;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.SchemaIntrospector;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查：模型、数据库连通性、表清单、关键配置。 */
@RestController
public class HealthController {

    private final T2SqlProperties properties;
    private final SchemaIntrospector schema;

    public HealthController(T2SqlProperties properties, SchemaIntrospector schema) {
        this.properties = properties;
        this.schema = schema;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", LocalDateTime.now().withNano(0).toString());
        body.put("stack", "Spring Boot + Spring MVC + MyBatis-Plus + AgentScope");
        body.put("model", properties.getLlm().getModel());
        body.put("llmBaseUrl", properties.getLlm().getBaseUrl());
        body.put("llmApiKeyConfigured", !properties.getLlm().effectiveApiKey().isBlank());
        body.put("maxRows", properties.getSql().getMaxRows());
        body.put("allowedTables", properties.getSql().getAllowedTables());
        try {
            long start = System.currentTimeMillis();
            schema.refresh();
            List<String> tables = schema.tableNames();
            body.put("database", schema.schemaName());
            body.put("tables", tables);
            body.put("databaseConnected", true);
            body.put("databaseLatencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            body.put("status", "DEGRADED");
            body.put("databaseConnected", false);
            body.put("databaseError", e.getMessage());
        }
        return body;
    }
}
