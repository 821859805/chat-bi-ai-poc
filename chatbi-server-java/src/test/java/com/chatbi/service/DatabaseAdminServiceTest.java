package com.chatbi.service;

import com.chatbi.model.ColumnInfo;
import com.chatbi.model.CommentUpdate;
import com.chatbi.model.DatabaseConnection;
import com.chatbi.model.TableInfo;
import com.chatbi.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseAdminServiceTest {

    @Mock
    private DatabaseConnectionService databaseConnectionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatabaseAdminService databaseAdminService;

    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        connection = new DatabaseConnection(
            "conn-1",
            "默认数据库",
            "localhost",
            3306,
            "user",
            "password",
            "test_db",
            "utf8mb4",
            "desc",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }

    private MockedStatic<DriverManager> mockDriver(DatabaseConnection conn) throws SQLException {
        MockedStatic<DriverManager> mockedStatic = mockStatic(DriverManager.class);
        String url = String.format(
            "jdbc:mariadb://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false",
            conn.getHost(), conn.getPort(), conn.getDatabaseName(), conn.getCharsetName()
        );
        Connection jdbcConnection = mock(Connection.class);
        mockedStatic.when(() -> DriverManager.getConnection(url, conn.getUsername(), conn.getPassword()))
            .thenReturn(jdbcConnection);
        return mockedStatic;
    }

    @Test
    void getTables_shouldReturnMetadata() throws Exception {
        when(databaseConnectionService.getConnection("conn-1")).thenReturn(Optional.of(connection));

        try (MockedStatic<DriverManager> driver = mockDriver(connection)) {
            when(jdbcTemplate.query(contains("information_schema.TABLES"), any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var mapper = (org.springframework.jdbc.core.RowMapper<TableInfo>) invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(anyString())).thenAnswer(rsInvocation -> {
                        String column = rsInvocation.getArgument(0);
                        return switch (column) {
                            case "table_name" -> "orders";
                            case "table_comment" -> "订单表";
                            case "engine" -> "InnoDB";
                            case "charset" -> "utf8mb4";
                            default -> "";
                        };
                    });
                    when(rs.getInt("table_rows")).thenReturn(5);
                    when(rs.getObject("table_size")).thenReturn(1.23);
                    TableInfo tableInfo = mapper.mapRow(rs, 0);
                    return List.of(tableInfo);
                });

            List<TableInfo> tables = databaseAdminService.getTables("conn-1");

            assertThat(tables).hasSize(1);
            assertThat(tables.getFirst().getTableName()).isEqualTo("orders");
        }
    }

    @Test
    void getTables_shouldThrowWhenConnectionMissing() {
        when(databaseConnectionService.getConnection("missing"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> databaseAdminService.getTables("missing"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTableSchema_shouldReturnSchema() throws Exception {
        when(databaseConnectionService.getConnection("conn-1")).thenReturn(Optional.of(connection));

        when(jdbcTemplate.queryForObject(contains("information_schema.TABLES"), eq(String.class), any(Object[].class)))
            .thenReturn("订单表");

        when(jdbcTemplate.query(contains("information_schema.COLUMNS"), any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var mapper = (org.springframework.jdbc.core.RowMapper<ColumnInfo>) invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(rsInvocation -> {
                    String column = rsInvocation.getArgument(0);
                    return switch (column) {
                        case "column_name" -> "id";
                        case "data_type" -> "bigint";
                        case "is_nullable" -> "NO";
                        case "column_key" -> "PRI";
                        case "column_default" -> null;
                        case "extra" -> "auto_increment";
                        case "column_comment" -> "主键";
                        default -> "";
                    };
                });
                when(rs.getInt("column_order")).thenReturn(1);
                ColumnInfo columnInfo = mapper.mapRow(rs, 0);
                return List.of(columnInfo);
            });

        try (MockedStatic<DriverManager> driver = mockDriver(connection)) {
            TableSchema schema = databaseAdminService.getTableSchema("conn-1", "orders");

            assertThat(schema.getTableComment()).isEqualTo("订单表");
            assertThat(schema.getColumns()).hasSize(1);
            assertThat(schema.getColumns().getFirst().getColumnName()).isEqualTo("id");
        }
    }

    @Test
    void updateComment_shouldUpdateColumnComment() throws Exception {
        when(databaseConnectionService.getConnection("conn-1")).thenReturn(Optional.of(connection));
        CommentUpdate update = new CommentUpdate();
        update.setTableName("orders");
        update.setColumnName("status");
        update.setComment("订单状态");

        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
            "COLUMN_TYPE", "varchar(32)",
            "IS_NULLABLE", "NO",
            "COLUMN_DEFAULT", null,
            "EXTRA", ""
        ));

        doNothing().when(jdbcTemplate).execute(anyString());

        try (MockedStatic<DriverManager> driver = mockDriver(connection)) {
            boolean success = databaseAdminService.updateComment("conn-1", update);

            assertThat(success).isTrue();
            verify(jdbcTemplate).execute(contains("COMMENT"));
        }
    }

    @Test
    void executeCustomSql_shouldReturnQueryResult() throws Exception {
        when(databaseConnectionService.getConnection("conn-1")).thenReturn(Optional.of(connection));
        when(jdbcTemplate.queryForList("SELECT 1")).thenReturn(List.of(Map.of("value", 1)));

        try (MockedStatic<DriverManager> driver = mockDriver(connection)) {
            Map<String, Object> result = databaseAdminService.executeCustomSql("conn-1", "SELECT 1");

            assertThat(result).containsEntry("success", true);
            assertThat(result).containsEntry("row_count", 1);
        }
    }

    @Test
    void executeCustomSql_shouldHandleUpdateStatements() throws Exception {
        when(databaseConnectionService.getConnection("conn-1")).thenReturn(Optional.of(connection));
        when(jdbcTemplate.update("UPDATE orders SET status='DONE'"))
            .thenReturn(2);

        try (MockedStatic<DriverManager> driver = mockDriver(connection)) {
            Map<String, Object> result = databaseAdminService.executeCustomSql("conn-1", "UPDATE orders SET status='DONE'");

            assertThat(result).containsEntry("row_count", 2);
            assertThat(result).containsEntry("success", true);
            assertThat(result.get("data")).isNull();
        }
    }
}
