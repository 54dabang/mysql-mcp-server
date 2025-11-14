package com.zcckj.mcp.mysql.config;


import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Configuration
@Data
public class DataBaseLimitConfig {
    @Value("${config.database.read-only-tables}")
    private String limitTables;

    @Value("${config.database.read-rows-limit}")
    private String limitRowsNumberStr;

    public List<String> getReadOnlyTables() {
        return Optional.ofNullable(limitTables)
                .filter(s -> !s.isEmpty())
                .map(tables -> Arrays.asList(tables.split(",")))
                .orElse(null);
    }

    public Integer getLimitRows(){
        return Integer.parseInt(limitRowsNumberStr);
    }



}
