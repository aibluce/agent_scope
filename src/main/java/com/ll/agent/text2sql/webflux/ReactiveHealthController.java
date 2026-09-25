package com.ll.agent.text2sql.webflux;

import com.ll.agent.text2sql.config.T2SqlProperties;
import com.ll.agent.text2sql.db.SchemaIntrospector;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux 版健康检查。
 *
 * <p>查库是阻塞调用，包一层 {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}，
 * 避免占住 Reactor 的事件循环线程——这正是把阻塞式技术栈（MyBatis/JDBC）
 * 放进响应式应用时的标准做法。
 */
@RestController
public class ReactiveHealthController {

    private final T2SqlProperties properties;
    private final SchemaIntrospector schemaIntrospector;

    public ReactiveHealthController(
            T2SqlProperties properties, SchemaIntrospector schemaIntrospector) {
        this.properties = properties;
        this.schemaIntrospector = schemaIntrospector;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.fromCallable(this::healthBody).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> healthBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", LocalDateTime.now().withNano(0).toString());
        body.put("stack", "Spring Boot + WebFlux (Reactor Netty) + MyBatis-Plus + AgentScope");
        body.put("webStack", "reactive");
        body.put("model", properties.getLlm().getModel());
        body.put("maxRows", properties.getSql().getMaxRows());
        body.put("allowedTables", properties.getSql().getAllowedTables());
        try {
            schemaIntrospector.refresh();
            body.put("database", schemaIntrospector.schemaName());
            body.put("tables", schemaIntrospector.tableNames());
            body.put("databaseConnected", true);
        } catch (Exception e) {
            body.put("status", "DEGRADED");
            body.put("databaseConnected", false);
            body.put("databaseError", e.getMessage());
        }
        return body;
    }
}
