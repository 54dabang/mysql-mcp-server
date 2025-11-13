package com.zcckj.mcp.mysql.service;

import com.zcckj.mcp.mysql.config.DataBaseConfig;
import com.zcckj.mcp.mysql.model.DbTool;
import com.zcckj.mcp.mysql.model.Resource;
import com.zcckj.mcp.mysql.model.TextContent;
import com.zcckj.mcp.mysql.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


import com.zcckj.mcp.mysql.config.DataBaseConfig;
import com.zcckj.mcp.mysql.model.DbTool;
import com.zcckj.mcp.mysql.model.Resource;
import com.zcckj.mcp.mysql.model.TextContent;
import com.zcckj.mcp.mysql.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MySQL MCP Server Service
 * 提供MySQL数据库的只读访问功能，包括表查询、统计、Schema获取等
 * 严格限制只允许SELECT、SHOW等只读操作，禁止任何修改数据或表结构的操作
 */
@Service
@Slf4j
public class MysqlMcpServerService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataBaseConfig databaseConfig;

    @Tool(description = "List the available tables", name = "list_tables")
    public String listTables() {
        final String database = "ledger";
        log.info("数据库:{}", database);
        try {
            // 通过一次SQL查询获取表名和注释信息
            List<Map<String, Object>> tableInfoList = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?",
                    database);

            if (tableInfoList.isEmpty()) {
                return JsonUtils.toJsonString(new TextContent("No tables found in database: " + database, "text"));
            }

            List<Resource> resources = tableInfoList.stream()
                    .map(row -> {
                        String tableName = (String) row.get("TABLE_NAME");
                        String tableComment = (String) row.get("TABLE_COMMENT");
                        String description = (tableComment != null && !tableComment.isEmpty())
                                ? tableComment
                                : "Data in table: " + tableName;
                        return Resource.builder()
                                .name("Table: " + tableName)
                                .description(description)
                                .build();
                    })
                    .collect(Collectors.toList());

            log.info("source:{}", resources);
            return JsonUtils.toJsonString(resources);
        } catch (Exception e) {
            log.error("Failed to list resources", e);
            throw new RuntimeException("Database error: " + e.getMessage());
        }
    }


    /**
     * 列出MySQL服务器中的所有数据库
     *
     * @return 返回JSON格式的数据库列表
     */
   /* @Tool(description = "列出MySQL服务器中所有可用的数据库。返回数据库列表，每个数据库包含URI、名称和描述。适用于需要查看服务器上有哪些数据库的场景。",
            name = "list_databases")*/
    public String listDatabases() {

        log.info("正在列出所有可用的数据库");

        try {
            // 查询所有数据库
            List<String> databases = jdbcTemplate.queryForList("SHOW DATABASES", String.class);

            // 构建资源列表
            List<Resource> resources = databases.stream()
                    .map(database -> Resource.builder()

                            .name("数据库: " + database)

                            .description("MySQL数据库: " + database)
                            .build())
                    .collect(Collectors.toList());

            log.info("成功列出 {} 个数据库", databases.size());
            return JsonUtils.toJsonString(resources);

        } catch (Exception e) {
            log.error("列出数据库时发生错误", e);
            return JsonUtils.toJsonString(
                    new TextContent("数据库错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 获取指定表的Schema信息（表结构）
     * 包括列名、数据类型、是否可为空、默认值、列注释等信息
     *
     * @param database 数据库名称
     * @param table    表名称
     * @return 返回JSON格式的表结构信息，以表格形式展示
     */
    @Tool(description = "获取指定表的详细Schema信息（表结构定义）。返回表的所有列信息，包括列名、数据类型、是否可为空、默认值和列注释。这对于理解表结构、编写SQL查询非常有用。",
            name = "get_table_schema")
    public String getTableSchema(
            /*@ToolParam(description = "数据库名称，例如：'ledger'、'test_db'")
            String database,*/
            @ToolParam(description = "表名称，例如：'users'、'orders'、'products'")
            String table) {
        final String database = "ledger";
        log.info("正在获取表Schema: {}.{}", database, table);

        try {
            // 从INFORMATION_SCHEMA查询表结构信息
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, " +
                            "COLUMN_KEY, EXTRA " +
                            "FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                            "ORDER BY ORDINAL_POSITION",
                    database, table);

            if (columns.isEmpty()) {
                log.warn("未找到表: {}.{}", database, table);
                return JsonUtils.toJsonString(new TextContent(
                        String.format("未找到表: %s.%s，请检查数据库名和表名是否正确", database, table),
                        "text"));
            }

            // 格式化表结构信息
            StringBuilder schemaInfo = new StringBuilder();
            schemaInfo.append(String.format("=== 表结构: %s.%s ===\n\n", database, table));
            schemaInfo.append(String.format("%-20s | %-15s | %-8s | %-15s | %-10s | %s\n",
                    "列名", "数据类型", "可为空", "默认值", "键类型", "备注"));
            schemaInfo.append("-".repeat(100)).append("\n");

            for (Map<String, Object> column : columns) {
                String columnName = String.valueOf(column.get("COLUMN_NAME"));
                String dataType = String.valueOf(column.get("DATA_TYPE"));
                String nullable = String.valueOf(column.get("IS_NULLABLE"));
                String defaultValue = column.get("COLUMN_DEFAULT") != null
                        ? String.valueOf(column.get("COLUMN_DEFAULT")) : "NULL";
                String columnKey = column.get("COLUMN_KEY") != null
                        ? String.valueOf(column.get("COLUMN_KEY")) : "";
                String comment = column.get("COLUMN_COMMENT") != null
                        ? String.valueOf(column.get("COLUMN_COMMENT")) : "";

                schemaInfo.append(String.format("%-20s | %-15s | %-8s | %-15s | %-10s | %s\n",
                        columnName, dataType, nullable, defaultValue, columnKey, comment));
            }

            log.info("成功获取表Schema: {}.{}, 共 {} 列", database, table, columns.size());
            return JsonUtils.toJsonString(new TextContent(schemaInfo.toString(), "text"));

        } catch (Exception e) {
            log.error("获取表Schema失败: {}.{}", database, table, e);
            return JsonUtils.toJsonString(new TextContent(
                    "获取表结构时发生错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 读取指定表的数据资源
     * 通过URI格式 mysql://database/table 来指定要读取的表
     *
     * @param uri 资源URI，格式为 mysql://database/table
     * @return 返回JSON格式的表数据，最多返回100条记录
     */
    /*@Tool(description = "通过URI读取指定表的数据。URI格式为 'mysql://数据库名/表名'，例如 'mysql://ledger/users'。默认返回最新的100条记录，以表格形式展示。适用于快速查看表中的数据内容。",
            name = "read_resource")*/
    public String readResource(
            @ToolParam(description = "资源URI，格式为 'mysql://数据库名/表名'，例如：'mysql://ledger/users' 或 'mysql://test_db/orders'")
            String uri) {

        log.info("正在读取资源: {}", uri);

        // 验证URI格式
        if (!uri.startsWith("mysql://")) {
            log.error("无效的URI协议: {}", uri);
            return JsonUtils.toJsonString(
                    new TextContent("无效的URI协议，必须以 'mysql://' 开头", "text"));
        }

        // 解析URI
        String[] parts = uri.substring("mysql://".length()).split("/");
        if (parts.length < 2) {
            log.error("无效的URI格式: {}", uri);
            return JsonUtils.toJsonString(
                    new TextContent("无效的URI格式，正确格式为: mysql://数据库名/表名", "text"));
        }

        String database = parts[0];
        String table = parts[1];

        try {
            // 查询表数据，限制返回100条
            String query = String.format(
                    "SELECT * FROM `%s`.`%s` LIMIT 100",
                    database, table);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(query);

            if (rows.isEmpty()) {
                log.info("表 {}.{} 中没有数据", database, table);
                return JsonUtils.toJsonString(
                        new TextContent("表 " + database + "." + table + " 中没有数据", "text"));
            }

            // 格式化输出
            StringBuilder result = new StringBuilder();
            result.append(String.format("=== 表数据: %s.%s (最多显示100条) ===\n\n", database, table));

            // 表头
            String headers = String.join(" | ", rows.get(0).keySet());
            result.append(headers).append("\n");
            result.append("-".repeat(headers.length())).append("\n");

            // 数据行
            for (Map<String, Object> row : rows) {
                String dataRow = row.values().stream()
                        .map(val -> val == null ? "NULL" : String.valueOf(val))
                        .collect(Collectors.joining(" | "));
                result.append(dataRow).append("\n");
            }

            log.info("成功读取表 {}.{} 的数据，共 {} 条记录", database, table, rows.size());
            return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));

        } catch (Exception e) {
            log.error("读取资源 {} 时发生错误", uri, e);
            return JsonUtils.toJsonString(
                    new TextContent("读取数据时发生错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 执行SQL查询
     * 仅允许只读操作（SELECT、SHOW、DESC、EXPLAIN等）
     * 严格禁止任何修改数据或表结构的操作
     *
     * @param sql SQL查询语句
     * @return 返回JSON格式的查询结果
     */
    @Tool(description = "执行SQL查询语句,提交参数为独立的sql,适用于执行复杂的数据查询和统计分析。",
            name = "execute_tool")
    public String executeSql(
            @ToolParam(description = "SQL查询语句。支持的操作：SELECT（数据查询）、SHOW（显示信息）、DESCRIBE/DESC（查看表结构）、EXPLAIN（查询分析）")
            String sql) {

        log.info("准备执行SQL: {}", sql);

        // 验证SQL语句的安全性
        if (!isReadOnlySqlQuery(sql)) {
            log.warn("拒绝执行非只读SQL: {}", sql);
            return JsonUtils.toJsonString(
                    new TextContent("安全限制：仅允许执行只读查询（SELECT、SHOW、DESC、EXPLAIN），禁止任何修改操作", "text"));
        }

        try {
            String normalizedQuery = sql.trim().toUpperCase();

            // 处理 SHOW TABLES 命令
            if (normalizedQuery.startsWith("SHOW TABLES")) {
                List<String> tables = jdbcTemplate.queryForList(sql, String.class);
                StringBuilder result = new StringBuilder();
                result.append("=== 数据表列表 ===\n\n");
                tables.forEach(table -> result.append(table).append("\n"));

                log.info("成功执行SHOW TABLES，返回 {} 个表", tables.size());
                return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));
            }

            // 处理 SHOW DATABASES 命令
            if (normalizedQuery.startsWith("SHOW DATABASES")) {
                List<String> databases = jdbcTemplate.queryForList(sql, String.class);
                StringBuilder result = new StringBuilder();
                result.append("=== 数据库列表 ===\n\n");
                databases.forEach(db -> result.append(db).append("\n"));

                log.info("成功执行SHOW DATABASES，返回 {} 个数据库", databases.size());
                return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));
            }

            // 处理 DESC/DESCRIBE 命令
            if (normalizedQuery.startsWith("DESC ") || normalizedQuery.startsWith("DESCRIBE ")) {
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql);
                StringBuilder result = new StringBuilder();
                result.append("=== 表结构信息 ===\n\n");

                if (!columns.isEmpty()) {
                    // 表头
                    String headers = String.join(" | ", columns.get(0).keySet());
                    result.append(headers).append("\n");
                    result.append("-".repeat(headers.length())).append("\n");

                    // 数据行
                    for (Map<String, Object> row : columns) {
                        String dataRow = row.values().stream()
                                .map(val -> val == null ? "NULL" : String.valueOf(val))
                                .collect(Collectors.joining(" | "));
                        result.append(dataRow).append("\n");
                    }
                }

                log.info("成功执行DESCRIBE命令");
                return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));
            }

            // 处理 SELECT 查询
            if (normalizedQuery.startsWith("SELECT")) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

                if (rows.isEmpty()) {
                    log.info("查询返回0条记录");
                    return JsonUtils.toJsonString(
                            new TextContent("查询成功，但没有找到匹配的数据", "text"));
                }

                StringBuilder result = new StringBuilder();
                result.append(String.format("=== 查询结果 (共 %d 条记录) ===\n\n", rows.size()));

                // 表头
                String headers = String.join(" | ", rows.get(0).keySet());
                result.append(headers).append("\n");
                result.append("-".repeat(headers.length())).append("\n");

                // 数据行
                for (Map<String, Object> row : rows) {
                    String dataRow = row.values().stream()
                            .map(val -> val == null ? "NULL" : String.valueOf(val))
                            .collect(Collectors.joining(" | "));
                    result.append(dataRow).append("\n");
                }

                log.info("成功执行SELECT查询，返回 {} 条记录", rows.size());
                return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));
            }

            // 其他只读命令
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            String result = JsonUtils.toJsonString(rows);

            log.info("成功执行SQL查询");
            return JsonUtils.toJsonString(new TextContent(result, "text"));

        } catch (Exception e) {
            log.error("执行SQL时发生错误: {}", sql, e);
            return JsonUtils.toJsonString(
                    new TextContent("SQL执行错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 统计指定表的记录数
     *
     * @param database 数据库名称
     * @param table    表名称
     * @return 返回表的记录总数
     */
    /*@Tool(description = "统计指定表中的记录总数。快速获取表中有多少条数据，等同于执行 'SELECT COUNT(*) FROM table'。适用于需要了解表数据规模的场景。",
            name = "count_table_rows")*/
    public String countTableRows(
            @ToolParam(description = "数据库名称，例如：'ledger'、'test_db'")
            String database,
            @ToolParam(description = "表名称，例如：'users'、'orders'")
            String table) {

        log.info("正在统计表记录数: {}.{}", database, table);

        try {
            String query = String.format("SELECT COUNT(*) FROM `%s`.`%s`", database, table);
            Long count = jdbcTemplate.queryForObject(query, Long.class);

            String result = String.format("表 %s.%s 共有 %d 条记录", database, table, count);
            log.info(result);

            return JsonUtils.toJsonString(new TextContent(result, "text"));

        } catch (Exception e) {
            log.error("统计表记录数失败: {}.{}", database, table, e);
            return JsonUtils.toJsonString(
                    new TextContent("统计记录数时发生错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 获取指定表的索引信息
     *
     * @param database 数据库名称
     * @param table    表名称
     * @return 返回表的索引详细信息
     */
    /*@Tool(description = "获取指定表的索引信息。返回表的所有索引，包括索引名称、列名、索引类型等。这对于理解表的性能优化和查询效率很有帮助。",
            name = "get_table_indexes")*/
    public String getTableIndexes(
            @ToolParam(description = "数据库名称，例如：'ledger'、'test_db'")
            String database,
            @ToolParam(description = "表名称，例如：'users'、'orders'")
            String table) {

        log.info("正在获取表索引信息: {}.{}", database, table);

        try {
            String query = String.format("SHOW INDEX FROM `%s`.`%s`", database, table);
            List<Map<String, Object>> indexes = jdbcTemplate.queryForList(query);

            if (indexes.isEmpty()) {
                return JsonUtils.toJsonString(
                        new TextContent("表 " + database + "." + table + " 没有索引", "text"));
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("=== 表索引: %s.%s ===\n\n", database, table));

            for (Map<String, Object> index : indexes) {
                result.append(String.format("索引名称: %s\n", index.get("Key_name")));
                result.append(String.format("  列名: %s\n", index.get("Column_name")));
                result.append(String.format("  唯一性: %s\n",
                        "0".equals(String.valueOf(index.get("Non_unique"))) ? "唯一" : "非唯一"));
                result.append(String.format("  索引类型: %s\n", index.get("Index_type")));
                result.append("\n");
            }

            log.info("成功获取表索引信息: {}.{}", database, table);
            return JsonUtils.toJsonString(new TextContent(result.toString(), "text"));

        } catch (Exception e) {
            log.error("获取表索引失败: {}.{}", database, table, e);
            return JsonUtils.toJsonString(
                    new TextContent("获取索引信息时发生错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 验证SQL查询是否为只读操作
     * 严格检查SQL语句，只允许SELECT、SHOW、DESC、EXPLAIN等只读操作
     * 禁止任何可能修改数据或表结构的操作
     *
     * @param query SQL查询语句
     * @return 如果是只读查询返回true，否则返回false
     */
    /**
     * 验证SQL查询是否为只读操作
     * 严格检查SQL语句，只允许SELECT、SHOW、DESC、EXPLAIN等只读操作
     * 禁止任何可能修改数据或表结构的操作
     *
     * @param query SQL查询语句
     * @return 如果是只读查询返回true，否则返回false
     */
    private boolean isReadOnlySqlQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }

        // 转换为小写并去除首尾空格
        String lowerQuery = query.toLowerCase().trim();

        // 允许的只读SQL关键字
        String[] allowedKeywords = {
                "select",      // 查询数据
                "show",        // 显示信息（表、数据库、列等）
                "desc",        // 查看表结构
                "describe",    // 查看表结构（完整形式）
                "explain"      // 查询分析
        };

        // 检查是否以允许的关键字开头
        boolean startsWithAllowed = Arrays.stream(allowedKeywords)
                .anyMatch(keyword -> lowerQuery.startsWith(keyword + " ") || lowerQuery.equals(keyword));

        if (!startsWithAllowed) {
            return false;
        }

        // 禁止的危险关键字（这些操作会修改数据或表结构）
        String[] forbiddenKeywords = {
                "insert",      // 插入数据
                "update",      // 更新数据
                "delete",      // 删除数据
                "drop",        // 删除表或数据库
                "create",      // 创建表或数据库
                "alter",       // 修改表结构
                "truncate",    // 清空表
                "replace",     // 替换数据
                "grant",       // 授权
                "revoke",      // 撤销权限
                "rename",      // 重命名
                "load",        // 加载数据
                "call",        // 调用存储过程
                "execute",     // 执行
                "exec"         // 执行（简写）
        };

        // 更精确地检查禁止关键字，确保它们是独立的SQL关键字而不是字段名的一部分
        for (String forbidden : forbiddenKeywords) {
            // 使用正则表达式匹配独立的SQL关键字
            // \b 表示单词边界，确保匹配的是独立的关键字而不是字段名的一部分
            String pattern = "\\b" + forbidden + "\\b";
            if (lowerQuery.matches(".*" + pattern + ".*")) {
                log.warn("检测到禁止的SQL操作，包含禁止关键字: {}，完整SQL: {}", forbidden, query);
                return false;
            }
        }

        // 额外检查：禁止包含分号的多语句执行（除了以分号结尾的情况）
        if (lowerQuery.contains(";") && !lowerQuery.trim().endsWith(";")) {
            // 检查是否包含多个语句（以分号分隔）
            String[] statements = lowerQuery.split(";");
            if (statements.length > 1) {
                // 检查除了最后一个语句外，其他语句是否为空
                boolean hasMultipleStatements = false;
                for (int i = 0; i < statements.length - 1; i++) {
                    if (!statements[i].trim().isEmpty()) {
                        hasMultipleStatements = true;
                        break;
                    }
                }
                if (hasMultipleStatements) {
                    log.warn("检测到多语句SQL，可能存在安全风险: {}", query);
                    return false;
                }
            }
        }

        return true;
    }
}
