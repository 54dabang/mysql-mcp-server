package com.zcckj.mcp.mysql.service;

import com.zcckj.mcp.mysql.config.DataBaseConfig;
import com.zcckj.mcp.mysql.config.DataBaseLimitConfig;
import com.zcckj.mcp.mysql.model.DbTool;
import com.zcckj.mcp.mysql.model.Resource;
import com.zcckj.mcp.mysql.model.TextContent;
import com.zcckj.mcp.mysql.utils.JsonUtils;
import com.zcckj.mcp.mysql.vo.TableSchemaVO;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.beans.factory.annotation.Autowired;

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
    @Autowired
    private DataBaseLimitConfig dataBaseLimitConfig;


    /**
     * 获取表的ddl信息
     * @param database
     * @param table
     * @return
     */
    public String getTableDDL(String database,String table){
        try {
            // 使用SHOW CREATE TABLE获取表的DDL语句
            String query = String.format("SHOW CREATE TABLE `%s`.`%s`", database, table);
            List<Map<String, Object>> result = jdbcTemplate.queryForList(query);

            if (result.isEmpty()) {
                log.warn("未找到表: {}.{}", database, table);
                return JsonUtils.toJsonString(new TextContent(
                        String.format("未找到表: %s.%s，请检查数据库名和表名是否正确", database, table),
                        "text"));
            }

            // 从结果中提取DDL语句
            Map<String, Object> row = result.get(0);
            String ddl = String.valueOf(row.get("Create Table"));

            StringBuilder ddlInfo = new StringBuilder();
            ddlInfo.append(ddl);

            log.info("成功获取表DDL: {}.{}", database, table);
            return ddl;

        } catch (Exception e) {
            log.error("获取表DDL失败: {}.{}", database, table, e);
            return null;
        }
    }

    @Tool(description = "获取所有可以读取的表schema信息",
            name = "get_available_table_schemas")
    public String getAvailableTableSchemas(){
        List<TableSchemaVO> tableSchemaVOList =new ArrayList<>();

        dataBaseLimitConfig.getReadOnlyTables().stream()
                .forEach(t->{
                    String ddl = getTableDDL(databaseConfig.getDatabase(),t);
                    tableSchemaVOList.add(TableSchemaVO.builder().tableName(t).ddl(ddl).build());
                });
        return JsonUtils.toJsonString(tableSchemaVOList);
    }

    @Tool(description = "执行SQL查询语句,提交参数为独立的sql,适用于执行复杂的数据查询和统计分析。",
            name = "execute_tool")
    public String executeSql(
            @ToolParam(description = "SQL查询语句。支持的操作：SELECT（数据查询）、SHOW（仅允许只读子集）")
            String sql) {

        log.info("准备执行SQL: {}", sql);

        // 验证SQL语句的安全性
        if (!isReadOnlySqlQuery(sql)) {
            log.warn("拒绝执行非只读SQL: {}", sql);
            return JsonUtils.toJsonString(
                    new TextContent("安全限制：仅允许执行只读查询（SELECT、SHOW 子集），禁止任何修改操作", "text"));
        }

        try {
            String normalizedQuery = sql.trim().toUpperCase();

            // 处理 SELECT 查询
            if (normalizedQuery.startsWith("SELECT")) {
                // 验证表访问权限并添加LIMIT限制
                String validatedSql = validateAndLimitSql(sql);
                if (validatedSql == null) {
                    log.warn("SQL访问了未授权的表: {}", sql);
                    return JsonUtils.toJsonString(
                            new TextContent("安全限制：只能查询授权的表。授权表列表: " +
                                    String.join(", ", dataBaseLimitConfig.getReadOnlyTables()), "text"));
                }

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(validatedSql);

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

            /* ---------- 2. SHOW 分支（新增白名单与禁用逻辑） ---------- */
            if (normalizedQuery.startsWith("SHOW")) {
                /* 2-1 显式禁用 SHOW DATABASES */
                if (normalizedQuery.matches("SHOW\\s+DATABASES.*")) {
                    log.warn("尝试执行被禁用的 SHOW DATABASES: {}", sql);
                    return JsonUtils.toJsonString(
                            new TextContent("安全限制：SHOW DATABASES 被禁用", "text"));
                }

                /* 2-2 SHOW TABLES -> 只返回白名单表 */
                if (normalizedQuery.matches("SHOW\\s+TABLES.*")) {
                    List<String> allowed = dataBaseLimitConfig.getReadOnlyTables();
                    /* 构造 IN 子句 */
                    String inClause = allowed.stream()
                            .map(t -> "'" + t + "'")
                            .collect(Collectors.joining(","));
                    String filtered = "SELECT TABLE_NAME as `Tables_in_" + "db" + "` " +
                            "FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = '" + databaseConfig.getDatabase() + "' " +
                            "  AND TABLE_NAME IN (" + inClause + ")";
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(filtered);
                    return JsonUtils.toJsonString(new TextContent(JsonUtils.toJsonString(rows), "text"));
                }

                /* 2-3 其它 SHOW 语句（含具体表）走表名校验 */
                String validatedShowSql = validateShowSql(sql);
                if (validatedShowSql == null) {
                    log.warn("SHOW 语句访问了未授权的表: {}", sql);
                    return JsonUtils.toJsonString(
                            new TextContent("安全限制：SHOW 语句只能操作授权的表。授权表列表: " +
                                    String.join(", ", dataBaseLimitConfig.getReadOnlyTables()), "text"));
                }
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                return JsonUtils.toJsonString(new TextContent(JsonUtils.toJsonString(rows), "text"));
            }

            return JsonUtils.toJsonString(
                    new TextContent(" 安全限制，不支持其他操作","text"));

            /* ---------- 3. 其余只读命令（DESC/EXPLAIN 等）直接放行 ---------- */
           /* List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            return JsonUtils.toJsonString(new TextContent(JsonUtils.toJsonString(rows), "text"));*/

        } catch (Exception e) {
            log.error("执行SQL时发生错误: {}", sql, e);
            return JsonUtils.toJsonString(new TextContent("SQL执行错误: " + e.getMessage(), "text"));
        }
    }

    /**
     * 验证SQL中的表访问权限并添加LIMIT限制
     * @param sql 原始SQL
     * @return 验证通过并添加LIMIT后的SQL，如果验证失败返回null
     */
    private String validateAndLimitSql(String sql) {
        try {
            // 解析SQL
            ByteArrayInputStream in = new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
            Statement statement = CCJSqlParserUtil.parse(in, StandardCharsets.UTF_8.name());


            if (!(statement instanceof Select)) {
                return null;
            }

            Select selectStatement = (Select) statement;

            // 提取所有表名
            Set<String> tablesInQuery = extractTableNames(selectStatement);

            // 验证表访问权限
            List<String> allowedTables = dataBaseLimitConfig.getReadOnlyTables();
            if (allowedTables != null && !allowedTables.isEmpty()) {
                // 将允许的表名转换为小写以便不区分大小写比较
                Set<String> allowedTablesLower = allowedTables.stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

                // 检查查询中的每个表是否都在允许列表中
                for (String table : tablesInQuery) {
                    if (!allowedTablesLower.contains(table.toLowerCase())) {
                        log.warn("未授权访问表: {}", table);
                        return null;
                    }
                }
            }

            // 添加或更新LIMIT限制
            Integer maxRows = dataBaseLimitConfig.getLimitRows();
            if (maxRows != null && maxRows > 0) {
                applyLimit(selectStatement, maxRows);
            }

            return selectStatement.toString();

        } catch (JSQLParserException e) {
            log.error("SQL解析失败: {}", sql, e);
            return null;
        }
    }

    /**
     * 提取SQL中的所有表名（包括JOIN的表）
     */
    private Set<String> extractTableNames(Select select) {
        Set<String> tables = new HashSet<>();

        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect) {
            extractTablesFromPlainSelect((PlainSelect) selectBody, tables);
        } else if (selectBody instanceof SetOperationList) {
            SetOperationList setOpList = (SetOperationList) selectBody;
            for (SelectBody sb : setOpList.getSelects()) {
                if (sb instanceof PlainSelect) {
                    extractTablesFromPlainSelect((PlainSelect) sb, tables);
                }
            }
        }

        return tables;
    }

    /**
     * 从PlainSelect中提取表名
     */
    private void extractTablesFromPlainSelect(PlainSelect plainSelect, Set<String> tables) {
        // 提取FROM子句中的表
        FromItem fromItem = plainSelect.getFromItem();
        if (fromItem instanceof Table) {
            tables.add(((Table) fromItem).getName());
        } else if (fromItem instanceof SubSelect) {
            // 处理子查询
            extractTableNames(((SubSelect) fromItem).getSelectBody(), tables);
        }

        // 提取JOIN子句中的表
        List<Join> joins = plainSelect.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                FromItem rightItem = join.getRightItem();
                if (rightItem instanceof Table) {
                    tables.add(((Table) rightItem).getName());
                } else if (rightItem instanceof SubSelect) {
                    extractTableNames(((SubSelect) rightItem).getSelectBody(), tables);
                }
            }
        }
    }

    /**
     * 递归提取SelectBody中的表名（用于子查询）
     */
    private void extractTableNames(SelectBody selectBody, Set<String> tables) {
        if (selectBody instanceof PlainSelect) {
            extractTablesFromPlainSelect((PlainSelect) selectBody, tables);
        } else if (selectBody instanceof SetOperationList) {
            SetOperationList setOpList = (SetOperationList) selectBody;
            for (SelectBody sb : setOpList.getSelects()) {
                extractTableNames(sb, tables);
            }
        }
    }

    /**
     * 为SQL添加或更新LIMIT限制
     */
    private void applyLimit(Select select, int maxRows) {
        SelectBody selectBody = select.getSelectBody();

        if (selectBody instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectBody;
            Limit existingLimit = plainSelect.getLimit();

            if (existingLimit != null) {
                // 如果已有LIMIT，取最小值
                Expression rowCount = existingLimit.getRowCount();
                if (rowCount instanceof LongValue) {
                    long existingRows = ((LongValue) rowCount).getValue();
                    if (existingRows > maxRows) {
                        existingLimit.setRowCount(new LongValue(maxRows));
                        log.info("原SQL LIMIT {} 超过限制，已调整为 {}", existingRows, maxRows);
                    }
                }
            } else {
                // 没有LIMIT，添加新的
                Limit newLimit = new Limit();
                newLimit.setRowCount(new LongValue(maxRows));
                plainSelect.setLimit(newLimit);
                log.info("为SQL添加LIMIT {}", maxRows);
            }
        } else if (selectBody instanceof SetOperationList) {
            // 对于UNION等操作，在最外层添加LIMIT
            SetOperationList setOpList = (SetOperationList) selectBody;
            Limit existingLimit = setOpList.getLimit();

            if (existingLimit != null) {
                Expression rowCount = existingLimit.getRowCount();
                if (rowCount instanceof LongValue) {
                    long existingRows = ((LongValue) rowCount).getValue();
                    if (existingRows > maxRows) {
                        existingLimit.setRowCount(new LongValue(maxRows));
                    }
                }
            } else {
                Limit newLimit = new Limit();
                newLimit.setRowCount(new LongValue(maxRows));
                setOpList.setLimit(newLimit);
            }
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
                "show"
               /* ,        // 显示信息（表、数据库、列等）
                "desc",        // 查看表结构
                "describe",    // 查看表结构（完整形式）
                "explain"      // 查询分析*/
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

    /**
     * 校验 SHOW 语句是否只访问了允许的表
     * 仅处理 SHOW COLUMNS FROM、SHOW INDEX FROM、SHOW CREATE TABLE 等带表名的场景
     */
    private String validateShowSql(String sql) {
        if (sql == null || !sql.trim().toLowerCase().startsWith("show")) {
            return null;
        }
        String lower = sql.trim().toLowerCase();
        String tableName = null;

        if (lower.matches("show\\s+(create|columns|index|keys|table status)\\s+(from|table)\\s+[`']?(\\w+)[`']?.*")) {
            tableName = lower.replaceAll("show\\s+(create|columns|index|keys|table status)\\s+(from|table)\\s+[`']?(\\w+)[`']?.*", "$3");
        }

        if (tableName == null) {
            // 不带表名的 SHOW 语句，此处已只剩 SHOW STATUS/SHOW VARIABLES 等，放行
            return sql;
        }

        Set<String> allowedTablesLower = dataBaseLimitConfig.getReadOnlyTables()
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (!allowedTablesLower.contains(tableName.toLowerCase())) {
            log.warn("SHOW 语句尝试访问未授权表: {}", tableName);
            return null;
        }
        return sql;
    }
}
