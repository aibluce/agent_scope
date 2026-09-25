package com.ll.agent.text2sql.model;

import com.ll.agent.text2sql.service.ApiException;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Text2SQL 请求体 —— 承载「用户输入的自然语言问题」。
 *
 * <p>POST /api/text2sql/ask 与 /api/text2sql/generate 共用；表单方式提交时同样绑定到本对象。
 */
@Data
public class Text2SqlRequest {

    /** 用户输入的问题（必填）。 */
    @NotBlank(message = "question 不能为空")
    private String question;

    /**
     * 会话 ID，可选。
     *
     * <p>不传时服务端每次生成一个独立会话（请求之间互不影响）；
     * 传入相同值即为多轮对话，智能体会记住上一轮的上下文。
     */
    private String sessionId;

    /** 是否追加「数据分析智能体」生成的自然语言结论，默认 true。 */
    private Boolean includeAnswer;

    /** 本次查询的行数上限，可选，受服务端 t2sql.sql.max-rows 约束。 */
    private Integer maxRows;

    public boolean includeAnswerOrDefault() {
        return includeAnswer == null || includeAnswer;
    }

    /** 取出校验过的问题文本。 */
    public String resolvedQuestion() {
        if (question == null || question.isBlank()) {
            throw new ApiException(400, "参数 question 不能为空");
        }
        return question.trim();
    }

    /** 会话 ID：未传时退化为一次性的请求级会话。 */
    public String resolvedSessionId(String fallback) {
        return (sessionId == null || sessionId.isBlank()) ? fallback : sessionId.trim();
    }
}
