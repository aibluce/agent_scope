package com.ll.agent.text2sql.model;

import lombok.Data;

/** information_schema.TABLES 的一行。 */
@Data
public class TableMeta {

    private String tableName;

    private String tableComment;
}
