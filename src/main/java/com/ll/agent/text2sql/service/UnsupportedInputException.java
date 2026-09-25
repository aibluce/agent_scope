package com.ll.agent.text2sql.service;

import java.util.List;
import lombok.Getter;

/**
 * 输入不被支持时抛出：用户输入不是「查询电商业务数据」的指令。
 *
 * <p>统一返回 HTTP 400，响应体里带 {@code rejected=true}、拒绝原因和可用的提问示例，
 * 前端可以据此提示「不支持其他指令」。
 */
@Getter
public class UnsupportedInputException extends ApiException {

    /** 拒绝类别：写操作 / 无关指令 / 提示词注入 / 越权查询 / 输入非法。 */
    private final String category;

    /** 可用的提问示例。 */
    private final List<String> examples;

    public UnsupportedInputException(String category, String reason, List<String> examples) {
        super(400, reason);
        this.category = category;
        this.examples = examples == null ? List.of() : examples;
    }
}
