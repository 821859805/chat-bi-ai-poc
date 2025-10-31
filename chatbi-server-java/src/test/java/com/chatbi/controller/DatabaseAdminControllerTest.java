package com.chatbi.controller;

import com.chatbi.model.*;
import com.chatbi.service.DatabaseAdminService;
import com.chatbi.service.DatabaseConnectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseAdminControllerTest {

    @Mock
    private DatabaseConnectionService databaseConnectionService;

    @Mock
    private DatabaseAdminService databaseAdminService;

    @InjectMocks
    private DatabaseAdminController controller;

    @Test
    void getDatabaseConnections_shouldReturnList() {
        DatabaseConnection connection = new DatabaseConnection("id", "默认", "localhost", 3306,
            "user", "pwd", "db", "utf8", "desc", true, LocalDateTime.now(), LocalDateTime.now());
        when(databaseConnectionService.getAllConnections()).thenReturn(List.of(connection));

        ResponseEntity<List<DatabaseConnection>> response = controller.getDatabaseConnections();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(connection);
    }

    @Test
    void getActiveDatabaseConnection_shouldReturnNotFoundWhenMissing() {
        when(databaseConnectionService.getActiveConnection()).thenReturn(null);

        ResponseEntity<DatabaseConnection> response = controller.getActiveDatabaseConnection();

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void getActiveDatabaseConnection_shouldReturnConnection() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("id");
        when(databaseConnectionService.getActiveConnection()).thenReturn(connection);

        ResponseEntity<DatabaseConnection> response = controller.getActiveDatabaseConnection();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(connection);
    }

    @Test
    void createDatabaseConnection_shouldDelegateToService() {
        DatabaseConnectionCreate payload = new DatabaseConnectionCreate(
            "name", "host", 3306, "user", "pwd", "db", "utf8", "desc");
        DatabaseConnection saved = new DatabaseConnection();
        saved.setId("id");
        when(databaseConnectionService.createConnection(payload)).thenReturn(saved);

        ResponseEntity<DatabaseConnection> response = controller.createDatabaseConnection(payload);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(saved);
    }

    @Test
    void getDatabaseConnection_shouldReturnNotFoundWhenMissing() {
        when(databaseConnectionService.getConnection("id")).thenReturn(Optional.empty());

        ResponseEntity<DatabaseConnection> response = controller.getDatabaseConnection("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void getDatabaseConnection_shouldReturnConnectionWhenPresent() {
        DatabaseConnection connection = new DatabaseConnection();
        when(databaseConnectionService.getConnection("id")).thenReturn(Optional.of(connection));

        ResponseEntity<DatabaseConnection> response = controller.getDatabaseConnection("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(connection);
    }

    @Test
    void updateDatabaseConnection_shouldReturnNotFoundWhenMissing() {
        DatabaseConnectionUpdate update = new DatabaseConnectionUpdate();
        when(databaseConnectionService.updateConnection("id", update)).thenReturn(null);

        ResponseEntity<DatabaseConnection> response = controller.updateDatabaseConnection("id", update);

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void updateDatabaseConnection_shouldReturnUpdatedEntity() {
        DatabaseConnectionUpdate update = new DatabaseConnectionUpdate();
        DatabaseConnection updated = new DatabaseConnection();
        when(databaseConnectionService.updateConnection("id", update)).thenReturn(updated);

        ResponseEntity<DatabaseConnection> response = controller.updateDatabaseConnection("id", update);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(updated);
    }

    @Test
    void deleteDatabaseConnection_shouldReturnNotFoundForMissingConnection() {
        when(databaseConnectionService.deleteConnection("id")).thenReturn(false);
        when(databaseConnectionService.getConnection("id")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.deleteDatabaseConnection("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void deleteDatabaseConnection_shouldReturnBadRequestWhenProtected() {
        when(databaseConnectionService.deleteConnection("id")).thenReturn(false);
        when(databaseConnectionService.getConnection("id")).thenReturn(Optional.of(new DatabaseConnection()));

        ResponseEntity<Map<String, String>> response = controller.deleteDatabaseConnection("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "Cannot delete the last database connection or default connection");
    }

    @Test
    void deleteDatabaseConnection_shouldReturnSuccess() {
        when(databaseConnectionService.deleteConnection("id")).thenReturn(true);

        ResponseEntity<Map<String, String>> response = controller.deleteDatabaseConnection("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("message", "Database connection deleted successfully");
    }

    @Test
    void testDatabaseConnection_shouldReturnTestResult() {
        Map<String, Object> result = Map.of("success", true);
        when(databaseConnectionService.testConnection(any(DatabaseConnectionTest.class))).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.testDatabaseConnection(new DatabaseConnectionTest());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(result);
    }

    @Test
    void getDatabaseTables_shouldHandleIllegalArgument() {
        when(databaseAdminService.getTables("id")).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<List<TableInfo>> response = controller.getDatabaseTables("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void getDatabaseTables_shouldHandleConnectionFailure() {
        when(databaseAdminService.getTables("id")).thenThrow(new RuntimeException("数据库连接失败"));

        ResponseEntity<List<TableInfo>> response = controller.getDatabaseTables("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(503);
    }

    @Test
    void getDatabaseTables_shouldReturnTables() {
        TableInfo table = new TableInfo();
        when(databaseAdminService.getTables("id")).thenReturn(List.of(table));

        ResponseEntity<List<TableInfo>> response = controller.getDatabaseTables("id");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(table);
    }

    @Test
    void getTableSchema_shouldHandleErrors() {
        when(databaseAdminService.getTableSchema("id", "users")).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<TableSchema> response = controller.getTableSchema("id", "users");

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void getTableSchema_shouldReturnSchema() {
        TableSchema schema = new TableSchema();
        when(databaseAdminService.getTableSchema("id", "users")).thenReturn(schema);

        ResponseEntity<TableSchema> response = controller.getTableSchema("id", "users");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(schema);
    }

    @Test
    void updateComment_shouldHandleNotFound() {
        when(databaseAdminService.updateComment(eq("id"), any(CommentUpdate.class))).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<Map<String, String>> response = controller.updateComment("id", new CommentUpdate());

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void updateComment_shouldReturnSuccessMessage() {
        when(databaseAdminService.updateComment(eq("id"), any(CommentUpdate.class))).thenReturn(true);

        ResponseEntity<Map<String, String>> response = controller.updateComment("id", new CommentUpdate());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("message", "Comment updated successfully");
    }

    @Test
    void updateComment_shouldReturnFailure() {
        when(databaseAdminService.updateComment(eq("id"), any(CommentUpdate.class))).thenReturn(false);

        ResponseEntity<Map<String, String>> response = controller.updateComment("id", new CommentUpdate());

        assertThat(response.getStatusCodeValue()).isEqualTo(500);
    }

    @Test
    void executeCustomSql_shouldRequireSql() {
        ResponseEntity<Map<String, Object>> response = controller.executeCustomSql("id", Map.of());

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void executeCustomSql_shouldHandleNotFound() {
        when(databaseAdminService.executeCustomSql("id", "SELECT 1")).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<Map<String, Object>> response = controller.executeCustomSql("id", Map.of("sql", "SELECT 1"));

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void executeCustomSql_shouldReturnResult() {
        Map<String, Object> result = Map.of("success", true);
        when(databaseAdminService.executeCustomSql("id", "SELECT 1")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.executeCustomSql("id", Map.of("sql", "SELECT 1"));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(result);
    }
}
