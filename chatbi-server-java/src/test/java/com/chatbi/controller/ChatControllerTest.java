package com.chatbi.controller;

import com.chatbi.model.*;
import com.chatbi.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private DatabaseAdminService databaseAdminService;

    @Mock
    private SchemaMetadataBuilder metadataBuilder;

    @Mock
    private UserWhitelistService userWhitelistService;

    @Mock
    private ChatSessionService chatSessionService;

    @Mock
    private ChatMessageService chatMessageService;

    @InjectMocks
    private ChatController controller;

    private ChatSession session;
    private SemanticSQL semanticSQL;

    @BeforeEach
    void setUp() {
        session = new ChatSession();
        session.setId(42L);
        session.setUserId("user-1");
        session.setTitle("hello");
        session.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        session.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));

        semanticSQL = new SemanticSQL(
            List.of("users"),
            List.of("users.id"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );
    }

    @Test
    void root_shouldReturnStaticInfo() {
        ResponseEntity<Map<String, Object>> response = controller.root();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "running");
    }

    @Test
    void healthCheck_shouldReturnHealthyWhenDatabaseAccessible() {
        when(databaseManager.getAllTables()).thenReturn(List.of("users", "orders"));

        ResponseEntity<Map<String, Object>> response = controller.healthCheck();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "healthy");
        assertThat(response.getBody()).containsEntry("tables_count", 2);
    }

    @Test
    void healthCheck_shouldReturnUnhealthyWhenDatabaseFails() {
        when(databaseManager.getAllTables()).thenThrow(new RuntimeException("db down"));

        ResponseEntity<Map<String, Object>> response = controller.healthCheck();

        assertThat(response.getStatusCodeValue()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("status", "unhealthy");
        assertThat(response.getBody()).containsEntry("error", "db down");
    }

    @Test
    void chat_shouldReturnForbiddenWhenTokenMissing() {
        ChatRequest request = new ChatRequest("你好", null, null);

        ResponseEntity<ChatResponse> response = controller.chat(null, request);

        assertThat(response.getStatusCodeValue()).isEqualTo(403);
        verifyNoInteractions(chatService, chatSessionService, chatMessageService);
    }

    @Test
    void chat_shouldCreateSessionAndReturnResponse() {
        ChatRequest request = new ChatRequest("第一次提问", null, null);
        ChatResponse pipelineResponse = new ChatResponse(
            "回答",
            "SELECT * FROM users",
            semanticSQL,
            "temp",
            Map.of("success", true),
            Map.of("debug", "info")
        );

        when(chatSessionService.createSession(eq("user-1"), anyString())).thenReturn(session);
        when(chatMessageService.appendUserMessage(eq(session), anyString())).thenReturn(new ChatMessage());
        when(chatService.processChatMessage(request)).thenReturn(pipelineResponse);
        when(chatMessageService.appendAssistantMessage(eq(session), anyString(), any(), any(), any(), any()))
            .thenReturn(new ChatMessage());

        ResponseEntity<ChatResponse> response = controller.chat("{\"userId\":\"user-1\"}", request);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        ChatResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getConversationId()).isEqualTo("42");
        assertThat(body.getSqlQuery()).isEqualTo("SELECT * FROM users");
        verify(chatMessageService).appendAssistantMessage(eq(session), eq("回答"), eq(semanticSQL), eq("SELECT * FROM users"),
            eq(Map.of("success", true)), eq(Map.of("debug", "info")));
    }

    @Test
    void executeSql_shouldUseSpecificConnectionAndUpdateHistory() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setName("默认");

        SQLExecutionRequest request = new SQLExecutionRequest("SELECT now()", "42", "conn-1");
        SQLExecutionResponse executionResponse = new SQLExecutionResponse(true, List.of(Map.of("now", "2025")), "", 1);

        when(databaseAdminService.getConnection("conn-1")).thenReturn(Optional.of(connection));
        when(databaseManager.executeQuery("SELECT now()", connection)).thenReturn(executionResponse);
        when(chatService.executeSqlAndUpdateResponse("42", "SELECT now()"))
            .thenReturn(Map.of("success", true));
        when(chatSessionService.getById(42L)).thenReturn(Optional.of(session));

        ResponseEntity<SQLExecutionResponse> response = controller.executeSql(request);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(executionResponse);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(chatMessageService).appendExecutionResultToLastAssistant(eq(session), captor.capture());
        assertThat(captor.getValue()).containsEntry("success", true);
    }

    @Test
    void executeSql_shouldPropagateErrorsAsRuntimeException() {
        SQLExecutionRequest request = new SQLExecutionRequest("SELECT 1", null, null);
        when(databaseManager.executeQuery("SELECT 1")).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> controller.executeSql(request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("执行SQL时发生错误");
    }

    @Test
    void getConversationHistory_shouldReturnHistory() {
        List<Map<String, Object>> history = List.of(Map.of("msg", "hello"));
        when(chatService.getConversationHistory("abc")).thenReturn(history);

        ResponseEntity<Map<String, Object>> response = controller.getConversationHistory("abc");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("history", history);
    }

    @Test
    void clearConversation_shouldDelegate() {
        ResponseEntity<Map<String, String>> response = controller.clearConversation("abc");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(chatService).clearConversation("abc");
    }

    @Test
    void getTables_shouldReturnList() {
        when(databaseManager.getAllTables()).thenReturn(List.of("users"));

        ResponseEntity<Map<String, List<String>>> response = controller.getTables();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("tables", List.of("users"));
    }

    @Test
    void getTableSchema_shouldReturnSchema() {
        List<Map<String, Object>> schema = List.of(Map.of("column", "id"));
        when(databaseManager.getTableSchema("users")).thenReturn(schema);

        ResponseEntity<Map<String, Object>> response = controller.getTableSchema("users");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("schema", schema);
    }

    @Test
    void getFullDatabaseSchema_shouldUseConnectionWhenProvided() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        when(databaseAdminService.getConnection("conn-1")).thenReturn(Optional.of(connection));

        TableInfo tableInfo = new TableInfo();
        tableInfo.setTableName("users");
        when(databaseAdminService.getTables("conn-1")).thenReturn(List.of(tableInfo));

        TableSchema tableSchema = new TableSchema();
        tableSchema.setColumns(List.of());
        when(databaseAdminService.getTableSchema("conn-1", "users")).thenReturn(tableSchema);

        ResponseEntity<Map<String, Object>> response = controller.getFullDatabaseSchema("conn-1");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("database_schema");
        Map<String, Object> schema = (Map<String, Object>) response.getBody().get("database_schema");
        assertThat(schema).containsKey("users");
    }

    @Test
    void getFullDatabaseSchema_shouldReturnNotFoundWhenConnectionMissing() {
        when(databaseAdminService.getConnection("missing")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getFullDatabaseSchema("missing");

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void getFullDatabaseSchema_shouldFallbackToDefaultConnection() {
        when(databaseManager.getAllTables()).thenReturn(List.of("orders"));
        when(databaseManager.getTableSchema("orders")).thenReturn(List.of(Map.of("column", "id")));

        ResponseEntity<Map<String, Object>> response = controller.getFullDatabaseSchema(null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> schema = (Map<String, Object>) response.getBody().get("database_schema");
        assertThat(schema).containsKey("orders");
    }

    @Test
    void getEnrichedMetadata_shouldReturnMetadata() {
        Map<String, Object> metadata = Map.of("tables", Map.of());
        when(metadataBuilder.buildDatabaseMetadata(null)).thenReturn(metadata);

        ResponseEntity<Map<String, Object>> response = controller.getEnrichedMetadata();

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("metadata", metadata);
    }

    @Test
    void getUserPermissions_shouldReturnDefaultsWhenTokenInvalid() {
        ResponseEntity<Map<String, Object>> response = controller.getUserPermissions(null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("hasDatabaseAccess", false);
    }

    @Test
    void getUserPermissions_shouldReturnRoleBasedFlags() {
        when(userWhitelistService.getUserRole("user-1")).thenReturn("ADMIN");

        ResponseEntity<Map<String, Object>> response = controller.getUserPermissions("{\"userId\":\"user-1\"}");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("hasDatabaseAccess", true);
        assertThat(response.getBody()).containsEntry("canDeleteDatabase", true);
        assertThat(response.getBody()).containsEntry("role", "ADMIN");
    }
}
