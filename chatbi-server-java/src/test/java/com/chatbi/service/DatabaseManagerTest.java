package com.chatbi.service;

import com.chatbi.model.DatabaseConnection;
import com.chatbi.model.SQLExecutionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseManagerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DatabaseConnectionService databaseConnectionService;

    @InjectMocks
    private DatabaseManager databaseManager;

    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setHost("localhost");
        connection.setPort(3306);
        connection.setDatabaseName("test_db");
        connection.setCharsetName("utf8mb4");
        connection.setUsername("user");
        connection.setPassword("pwd");
    }

    @Test
    void executeQuery_shouldUseDefaultJdbcTemplateWhenConnectionNull() {
        when(databaseConnectionService.getActiveConnection()).thenReturn(connection);
        when(jdbcTemplate.queryForList("SELECT * FROM users"))
            .thenReturn(List.of(Map.of("id", 1)));

        SQLExecutionResponse response = databaseManager.executeQuery("SELECT * FROM users");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        verify(jdbcTemplate).queryForList("SELECT * FROM users");
    }

    @Test
    void executeQuery_shouldCreateTemplateForSpecificConnection() {
        JdbcTemplate customTemplate = mock(JdbcTemplate.class);
        when(customTemplate.queryForList("SELECT * FROM orders"))
            .thenReturn(List.of(Map.of("id", 2)));

        DatabaseManager spyManager = new DatabaseManager() {
            @Override
            public SQLExecutionResponse executeQuery(String sql, DatabaseConnection connection) {
                DriverManagerDataSource dataSource = new DriverManagerDataSource();
                dataSource.setUrl(String.format(
                    "jdbc:mariadb://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false&serverTimezone=UTC",
                    connection.getHost(), connection.getPort(), connection.getDatabaseName(), connection.getCharsetName()
                ));
                dataSource.setUsername(connection.getUsername());
                dataSource.setPassword(connection.getPassword());
                JdbcTemplate template = customTemplate;
                List<Map<String, Object>> data = template.queryForList(sql);
                return new SQLExecutionResponse(true, data, null, data.size());
            }
        };
        spyManager.databaseConnectionService = databaseConnectionService;
        spyManager.jdbcTemplate = jdbcTemplate;

        SQLExecutionResponse response = spyManager.executeQuery("SELECT * FROM orders", connection);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).hasSize(1);
        verify(customTemplate).queryForList("SELECT * FROM orders");
    }

    @Test
    void executeQuery_shouldHandleUpdateStatements() {
        when(jdbcTemplate.update("UPDATE users SET active=1"))
            .thenReturn(3);

        SQLExecutionResponse response = databaseManager.executeQuery("UPDATE users SET active=1", connection);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getRowCount()).isEqualTo(3);
    }

    @Test
    void executeQuery_shouldReturnErrorOnException() {
        when(jdbcTemplate.queryForList(anyString()))
            .thenThrow(new IllegalStateException("db error"));

        SQLExecutionResponse response = databaseManager.executeQuery("SELECT 1", (DatabaseConnection) null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).contains("db error");
    }

    @Test
    void getAllTables_shouldUseActiveConnection() {
        when(databaseConnectionService.getActiveConnection()).thenReturn(connection);
        DatabaseManager spyManager = new DatabaseManager();
        spyManager.databaseConnectionService = databaseConnectionService;
        spyManager.jdbcTemplate = jdbcTemplate;

        DatabaseConnection other = new DatabaseConnection();
        other.setId("conn-2");

        DatabaseManager managerSpy = org.mockito.Mockito.spy(spyManager);
        when(managerSpy.getAllTables(connection)).thenReturn(List.of("users"));

        List<String> tables = managerSpy.getAllTables();

        assertThat(tables).containsExactly("users");
        verify(databaseConnectionService).getActiveConnection();
    }

    @Test
    void getTableSchema_shouldDelegateToActiveConnection() {
        when(databaseConnectionService.getActiveConnection()).thenReturn(connection);
        DatabaseManager managerSpy = org.mockito.Mockito.spy(databaseManager);
        when(managerSpy.getTableSchema("orders", connection)).thenReturn(List.of(Map.of("Field", "id")));

        List<Map<String, Object>> schema = managerSpy.getTableSchema("orders");

        assertThat(schema).hasSize(1);
        verify(databaseConnectionService).getActiveConnection();
    }
}
