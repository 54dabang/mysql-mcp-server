package com.zcckj.mcp.mysql.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TableSchemaVO {
    private String tableName;

    private String ddl;
}
