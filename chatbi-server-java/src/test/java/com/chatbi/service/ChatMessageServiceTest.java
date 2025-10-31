package com.chatbi.service;

import com.chatbi.model.ChatMessage;
import com.chatbi.model.ChatSession;
import com.chatbi.model.SemanticSQL;
import com.chatbi.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatSessionService chatSessionService;

    @InjectMocks
    private ChatMessageService chatMessageService;

    private ChatSession session;

    @BeforeEach
    void setUp() {
        session = new ChatSession();
        session.setId(1L);
        session.setUserId("user-1");
        session.setTitle("测试会话");
        session.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        session.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));
    }

    @Test
    void listMessages_shouldDelegateToRepository() {
        List<ChatMessage> messages = List.of(new ChatMessage());
        when(chatMessageRepository.findBySessionOrderByCreatedAtAsc(session)).thenReturn(messages);

        assertThat(chatMessageService.listMessages(session)).isEqualTo(messages);
    }

    @Test
    void appendUserMessage_shouldPersistUserEntry() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMessage saved = chatMessageService.appendUserMessage(session, "你好");

        verify(chatMessageRepository).save(captor.capture());
        verify(chatSessionService).touchUpdatedAt(session);

        ChatMessage message = captor.getValue();
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).isEqualTo("你好");
        assertThat(saved.getRole()).isEqualTo("user");
    }

    @Test
    void appendAssistantMessage_withMetadata_shouldStoreJsonStrings() {
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        SemanticSQL semanticSQL = new SemanticSQL(
            List.of("orders"),
            List.of("id"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );
        Map<String, Object> execution = Map.of("rows", 3);
        Map<String, Object> debug = Map.of("provider", "mock");

        ChatMessage message = chatMessageService.appendAssistantMessage(
            session,
            "生成完成",
            semanticSQL,
            "SELECT * FROM orders",
            execution,
            debug
        );

        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getSemanticSql()).contains("\"tables\":[\"orders\"]");
        assertThat(message.getExecutionResult()).contains("rows");
        assertThat(message.getDebugInfo()).contains("provider");
        verify(chatSessionService).touchUpdatedAt(session);
    }

    @Test
    void appendExecutionResultToLastAssistant_shouldUpdateMostRecentAssistantMessage() {
        ChatMessage userMessage = new ChatMessage();
        userMessage.setRole("user");
        userMessage.setContent("你好");

        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");

        List<ChatMessage> history = new ArrayList<>();
        history.add(userMessage);
        history.add(assistant);

        when(chatMessageRepository.findBySessionOrderByCreatedAtAsc(session)).thenReturn(history);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> execution = new HashMap<>();
        execution.put("success", true);

        chatMessageService.appendExecutionResultToLastAssistant(session, execution);

        assertThat(assistant.getExecutionResult()).contains("success");
        verify(chatMessageRepository, times(1)).save(assistant);
        verify(chatSessionService, times(1)).touchUpdatedAt(session);
    }
}
