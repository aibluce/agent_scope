package com.ll.agent.text2sql.model;

import lombok.Data;

/** 直接执行 SQL 的请求体：{"sql": "...", "maxRows": 100}。 */
@Data
public class SqlRequest {

    /** 待执行的只读 SQL。 */
    private String sql;

    /** 行数上限，可选。 */
    private Integer maxRows;
}
