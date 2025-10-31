package com.chatbi.service;

import com.chatbi.model.DatabaseConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaMetadataBuilderTest {

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private DatabaseConnectionService databaseConnectionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SchemaMetadataBuilder builder;
    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        builder = new SchemaMetadataBuilder();
        ReflectionTestUtils.setField(builder, "databaseManager", databaseManager);
        ReflectionTestUtils.setField(builder, "databaseConnectionService", databaseConnectionService);

        when(databaseManager.getJdbcTemplate()).thenReturn(jdbcTemplate);

        connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setDatabaseName("test_db");
        connection.setHost("localhost");
    }

    @Test
    void buildDatabaseMetadata_shouldAssembleStructure() {
        when(databaseManager.getAllTables(any(DatabaseConnection.class))).thenReturn(List.of("orders"));

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
            .thenReturn("订单数据");

        List<Map<String, Object>> columns = new ArrayList<>();
        Map<String, Object> idColumn = new HashMap<>();
        idColumn.put("name", "id");
        idColumn.put("type", "bigint");
        idColumn.put("comment", "主键");
        columns.add(idColumn);

        Map<String, Object> amountColumn = new HashMap<>();
        amountColumn.put("name", "amount");
        amountColumn.put("type", "decimal");
        amountColumn.put("comment", "金额");
        columns.add(amountColumn);

        when(jdbcTemplate.queryForList(anyString(), any(), any())).thenReturn(columns);
        when(jdbcTemplate.queryForList(eq("SELECT * FROM orders LIMIT 5"))).thenReturn(
            List.of(Map.of("id", 1L, "amount", 99.9))
        );

        Map<String, Object> metadata = builder.buildDatabaseMetadata(null);

        @SuppressWarnings("unchecked")
        Map<String, String> dbInfo = (Map<String, String>) metadata.get("db");
        assertThat(dbInfo).containsEntry("host", "localhost");
        assertThat(dbInfo).containsEntry("name", "test_db");

        @SuppressWarnings("unchecked")
        Map<String, Object> tables = (Map<String, Object>) metadata.get("tables");
        assertThat(tables).containsKey("orders");

        @SuppressWarnings("unchecked")
        Map<String, Object> orders = (Map<String, Object>) tables.get("orders");
        assertThat(orders.get("comment")).isEqualTo("订单数据");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrichedColumns = (List<Map<String, Object>>) orders.get("columns");
        assertThat(enrichedColumns.getFirst()).containsEntry("name", "id");
        assertThat(enrichedColumns.getFirst()).containsEntry("samples", List.of(1L));
    }

    @Test
    void summarizeMetadataForPrompt_shouldInferDescriptions() {
        Map<String, Object> table = new HashMap<>();
        table.put("comment", "");

        Map<String, Object> column1 = new HashMap<>();
        column1.put("name", "user_id");
        column1.put("type", "bigint");
        column1.put("comment", "");
        column1.put("samples", List.of(1001));

        Map<String, Object> column2 = new HashMap<>();
        column2.put("name", "created_at");
        column2.put("type", "datetime");
        column2.put("comment", "");
        column2.put("samples", List.of("2024-01-01T00:00:00"));

        table.put("columns", List.of(column1, column2));
        table.put("samples", List.of(Map.of(
            "user_id", 1001,
            "created_at", "2024-01-01T00:00:00",
            "status", "ACTIVE"
        )));

        Map<String, Object> metadata = Map.of(
            "tables", Map.of("user_logs", table)
        );

        String summary = builder.summarizeMetadataForPrompt(metadata);

        assertThat(summary).contains("用户相关数据");
        assertThat(summary).contains("主键/外键标识");
        assertThat(summary).contains("日期/时间");
        assertThat(summary).contains("样例: user_id=1001");
    }

    @Test
    void buildTableMetadata_shouldPopulateSamples() {
        List<Map<String, Object>> columns = List.of(
            new HashMap<>(Map.of("name", "id")),
            new HashMap<>(Map.of("name", "status"))
        );

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("test_db"), eq("orders")))
            .thenReturn("订单信息");
        when(jdbcTemplate.queryForList(eq("SELECT column_name, column_type, is_nullable, column_key, column_default, extra, column_comment "+
            "FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position"),
            eq("test_db"), eq("orders")))
            .thenReturn(columns);
        when(jdbcTemplate.queryForList(eq("SELECT * FROM orders LIMIT 5")))
            .thenReturn(List.of(Map.of("id", 1, "status", "PAID")));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = ReflectionTestUtils.invokeMethod(builder, "buildTableMetadata", "orders", connection);

        assertThat(metadata).containsEntry("comment", "订单信息");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrichedColumns = (List<Map<String, Object>>) metadata.get("columns");
        assertThat(enrichedColumns.get(0)).containsEntry("samples", List.of(1));
        assertThat(enrichedColumns.get(1)).containsEntry("samples", List.of("PAID"));
    }

    @Test
    void getTableComment_shouldReturnEmptyOnError() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        String comment = ReflectionTestUtils.invokeMethod(builder, "getTableComment", "orders", connection);

        assertThat(comment).isEmpty();
    }

    @Test
    void getColumnsWithComments_shouldReturnColumns() {
        List<Map<String, Object>> columns = List.of(Map.of("name", "id"));
        when(jdbcTemplate.queryForList(anyString(), any(), any()))
            .thenReturn(columns);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = ReflectionTestUtils.invokeMethod(builder, "getColumnsWithComments", "orders", connection);

        assertThat(result).containsExactly(Map.of("name", "id"));
    }

    @Test
    void getSampleRows_shouldReturnRows() {
        when(jdbcTemplate.queryForList("SELECT * FROM orders LIMIT 5"))
            .thenReturn(List.of(Map.of("id", 1)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = ReflectionTestUtils.invokeMethod(builder, "getSampleRows", "orders", 5, connection);

        assertThat(rows).containsExactly(Map.of("id", 1));
    }
}
