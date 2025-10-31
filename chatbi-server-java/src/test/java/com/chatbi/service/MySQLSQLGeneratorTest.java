package com.chatbi.service;

import com.chatbi.model.SemanticSQL;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLSQLGeneratorTest {

    private final MySQLSQLGenerator generator = new MySQLSQLGenerator();

    @Test
    void generateMySQLSQL_shouldBuildCompleteQuery() {
        Map<String, Object> condition1 = new HashMap<>();
        condition1.put("column", "orders.status");
        condition1.put("operator", "IN");
        condition1.put("value", List.of("PAID", "SHIPPED"));

        Map<String, Object> condition2 = new HashMap<>();
        condition2.put("column", "orders.created_at");
        condition2.put("operator", "BETWEEN");
        condition2.put("value", List.of("2024-01-01", "2024-12-31"));

        Map<String, Object> condition3 = new HashMap<>();
        condition3.put("column", "customers.name");
        condition3.put("operator", "LIKE");
        condition3.put("value", "%Alice%");

        Map<String, String> join = new HashMap<>();
        join.put("type", "LEFT");
        join.put("table1", "orders");
        join.put("table2", "customers");
        join.put("condition", "orders.customer_id = customers.id");

        Map<String, String> group = new HashMap<>();
        group.put("column", "orders.customer_id");
        group.put("direction", "ASC");

        Map<String, String> order = new HashMap<>();
        order.put("column", "total_amount");
        order.put("direction", "DESC");

        Map<String, String> aggregation = new HashMap<>();
        aggregation.put("function", "SUM");
        aggregation.put("column", "orders.amount");
        aggregation.put("alias", "total_amount");

        SemanticSQL semanticSQL = new SemanticSQL(
            List.of("orders", "customers"),
            List.of("orders.customer_id", "SUM(orders.amount) AS total_amount"),
            List.of(condition1, condition2, condition3),
            List.of(aggregation),
            List.of(join),
            List.of(order),
            List.of("orders.customer_id"),
            100
        );

        String sql = generator.generateMySQLSQL(semanticSQL);

        assertThat(sql).contains("SELECT orders.customer_id, SUM(orders.amount) AS total_amount");
        assertThat(sql).contains("FROM orders");
        assertThat(sql).contains("LEFT JOIN customers ON orders.customer_id = customers.id");
        assertThat(sql).contains("orders.status IN ('PAID', 'SHIPPED')");
        assertThat(sql).contains("orders.created_at BETWEEN '2024-01-01' AND '2024-12-31'");
        assertThat(sql).contains("customers.name LIKE '%Alice%'");
        assertThat(sql).contains("GROUP BY orders.customer_id");
        assertThat(sql).contains("ORDER BY total_amount DESC");
        assertThat(sql).endsWith("LIMIT 100");
    }

    @Test
    void generateMySQLSQL_shouldHandleMissingTables() {
        SemanticSQL semanticSQL = new SemanticSQL(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        String sql = generator.generateMySQLSQL(semanticSQL);

        assertThat(sql).isEqualTo("SELECT 1; -- No tables specified");
    }

    @Test
    void generateMySQLSQL_shouldHandleErrorGracefully() {
        SemanticSQL semanticSQL = new SemanticSQL(null, null, null, null, null, null, null, null);

        String sql = generator.generateMySQLSQL(semanticSQL);

        assertThat(sql).contains("Error generating SQL");
    }
}
