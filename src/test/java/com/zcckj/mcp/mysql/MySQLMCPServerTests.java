package com.zcckj.mcp.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcckj.mcp.mysql.model.DbTool;
import com.zcckj.mcp.mysql.model.TextContent;
import com.zcckj.mcp.mysql.service.MysqlMcpServerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class MySQLMCPServerTests {

    @Autowired
    private MysqlMcpServerService mcpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();




    @Test
    void testExecuteSqlTool() {
        // 测试有效的SQL查询
        String query = "SELECT * FROM purch_order.t_supplier_info LIMIT 10";
        String result = mcpClient.executeSql(query);
        assertThat(result).isNotNull();

        TextContent textContent = parseTextContent(result);
        assertThat(textContent.getText()).isNotNull();
    }



    @Test
    void testInvalidQuery() {
        // 测试无效的SQL查询（包含DROP关键字）
        String invalidQuery = "DROP TABLE test_table";
        String result = mcpClient.executeSql(invalidQuery);

        TextContent textContent = parseTextContent(result);
        assertThat(textContent.getText()).contains("Invalid SQL query");
    }

    private TextContent parseTextContent(String json) {
        try {
            return objectMapper.readValue(json, TextContent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析JSON失败: " + e.getMessage(), e);
        }
    }
}