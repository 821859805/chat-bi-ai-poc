package com.chatbi.service;

import com.chatbi.model.ChatRequest;
import com.chatbi.model.ChatResponse;
import com.chatbi.model.DatabaseConnection;
import com.chatbi.model.SemanticSQL;
import com.chatbi.model.SQLExecutionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private SemanticSQLConverter semanticSQLConverter;

    @Mock
    private MySQLSQLGenerator mySQLSQLGenerator;

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private DatabaseConnectionService databaseConnectionService;

    @InjectMocks
    private ChatService chatService;

    private DatabaseConnection activeConnection;
    private SemanticSQL semanticSQL;

    @BeforeEach
    void setUp() {
        activeConnection = new DatabaseConnection(
            "dbc-1",
            "默认数据库",
            "localhost",
            3306,
            "root",
            "pwd",
            "test_db",
            "utf8mb4",
            "desc",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()
        );

        Map<String, Object> condition = new HashMap<>();
        condition.put("column", "users.id");
        condition.put("operator", "=");
        condition.put("value", 1);

        Map<String, String> aggregation = new HashMap<>();
        aggregation.put("function", "SUM");
        aggregation.put("column", "orders.amount");
        aggregation.put("alias", "total");

        Map<String, String> join = new HashMap<>();
        join.put("type", "INNER");
        join.put("table1", "users");
        join.put("table2", "orders");
        join.put("condition", "users.id = orders.user_id");

        Map<String, String> orderBy = new HashMap<>();
        orderBy.put("column", "users.id");
        orderBy.put("direction", "DESC");

        semanticSQL = new SemanticSQL(
            List.of("users", "orders"),
            List.of("users.id", "orders.amount"),
            List.of(condition),
            List.of(aggregation),
            List.of(join),
            List.of(orderBy),
            List.of("users.id"),
            10
        );
    }

    @Test
    void processChatMessage_shouldCreateConversationAndReturnResponse() {
        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(anyString(), any())).thenReturn(semanticSQL);
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of("provider", "mock"));
        when(mySQLSQLGenerator.generateMySQLSQL(semanticSQL)).thenReturn("SELECT * FROM users");

        ChatRequest request = new ChatRequest("查询用户", null, null);

        ChatResponse response = chatService.processChatMessage(request);

        assertThat(response.getSqlQuery()).isEqualTo("SELECT * FROM users");
        assertThat(response.getSemanticSql()).isEqualTo(semanticSQL);
        assertThat(response.getConversationId()).isNotBlank();

        List<Map<String, Object>> history = chatService.getConversationHistory(response.getConversationId());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().get("role")).isEqualTo("user");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
        assertThat(history.get(1).get("semantic_sql")).isEqualTo(semanticSQL);
    }

    @Test
    void processChatMessage_shouldReuseConversationAndIncludeShortContext() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(promptCaptor.capture(), any())).thenReturn(semanticSQL);
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of());
        when(mySQLSQLGenerator.generateMySQLSQL(semanticSQL)).thenReturn("SELECT 1");

        ChatResponse first = chatService.processChatMessage(new ChatRequest("第一次", null, null));

        ChatRequest secondRequest = new ChatRequest("第二次", first.getConversationId(), null);
        ChatResponse second = chatService.processChatMessage(secondRequest);

        assertThat(second.getConversationId()).isEqualTo(first.getConversationId());
        assertThat(promptCaptor.getAllValues()).hasSize(2);
        assertThat(promptCaptor.getAllValues().get(1)).contains("上一次用户输入（供参考）");

        List<Map<String, Object>> history = chatService.getConversationHistory(first.getConversationId());
        assertThat(history).hasSize(4);
    }

    @Test
    void processChatMessage_shouldHandleExceptionGracefully() {
        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(anyString(), any()))
            .thenThrow(new RuntimeException("LLM error"));
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of("error", "LLM error"));

        ChatResponse response = chatService.processChatMessage(new ChatRequest("查询", null, null));

        assertThat(response.getSqlQuery()).isNull();
        assertThat(response.getSemanticSql()).isNull();
        assertThat(response.getConversationId()).isNotBlank();
        assertThat(response.getResponse()).contains("处理消息时发生错误");
    }

    @Test
    void executeSqlAndUpdateResponse_shouldReturnExecutionOutcome() {
        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(anyString(), any())).thenReturn(semanticSQL);
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of());
        when(mySQLSQLGenerator.generateMySQLSQL(semanticSQL)).thenReturn("SELECT 1");

        ChatResponse response = chatService.processChatMessage(new ChatRequest("查询", null, null));

        SQLExecutionResponse executionResponse = new SQLExecutionResponse(
            true,
            List.of(Map.of("id", 1)),
            "",
            1
        );

        when(databaseManager.executeQuery("SELECT now()", activeConnection)).thenReturn(executionResponse);

        Map<String, Object> result = chatService.executeSqlAndUpdateResponse(response.getConversationId(), "SELECT now()");

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("row_count", 1);

        List<Map<String, Object>> history = chatService.getConversationHistory(response.getConversationId());
        Map<String, Object> assistant = history.getLast();
        assertThat(assistant.get("execution_result")).isEqualTo(executionResponse);
    }

    @Test
    void executeSqlAndUpdateResponse_shouldHandleFailures() {
        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(anyString(), any())).thenReturn(semanticSQL);
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of());
        when(mySQLSQLGenerator.generateMySQLSQL(semanticSQL)).thenReturn("SELECT 1");

        ChatResponse response = chatService.processChatMessage(new ChatRequest("查询", null, null));

        when(databaseManager.executeQuery(anyString(), any(DatabaseConnection.class)))
            .thenThrow(new RuntimeException("db failure"));

        Map<String, Object> result = chatService.executeSqlAndUpdateResponse(response.getConversationId(), "SELECT now()");

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("error")).isEqualTo("db failure");
    }

    @Test
    void clearConversation_shouldRemoveHistory() {
        when(databaseConnectionService.getConnection(anyString())).thenReturn(Optional.empty());
        when(databaseConnectionService.getActiveConnection()).thenReturn(activeConnection);
        when(semanticSQLConverter.convertToSemanticSQL(anyString(), any())).thenReturn(semanticSQL);
        when(semanticSQLConverter.getLastDebug()).thenReturn(Map.of());
        when(mySQLSQLGenerator.generateMySQLSQL(semanticSQL)).thenReturn("SELECT 1");

        ChatResponse response = chatService.processChatMessage(new ChatRequest("查询", null, null));

        assertThat(chatService.getConversationHistory(response.getConversationId())).isNotEmpty();

        chatService.clearConversation(response.getConversationId());

        assertThat(chatService.getConversationHistory(response.getConversationId())).isEmpty();
    }
}
