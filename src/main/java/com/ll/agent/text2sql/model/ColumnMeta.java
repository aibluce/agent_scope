package com.ll.agent.text2sql.model;

import lombok.Data;

/** information_schema.COLUMNS 的一行。 */
@Data
public class ColumnMeta {

    private String tableName;

    private String columnName;

    private String columnType;

    private String isNullable;

    private String columnKey;

    private String columnComment;

    private String columnDefault;
}
