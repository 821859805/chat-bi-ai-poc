package com.chatbi.controller;

import com.chatbi.model.ChatMessage;
import com.chatbi.model.ChatSession;
import com.chatbi.service.ChatMessageService;
import com.chatbi.service.ChatSessionService;
import com.chatbi.service.UserWhitelistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private ChatSessionService chatSessionService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private UserWhitelistService userWhitelistService;

    @InjectMocks
    private SessionController controller;

    private ChatSession session;

    @BeforeEach
    void setUp() {
        session = new ChatSession();
        session.setId(1L);
        session.setUserId("user-1");
        session.setTitle("历史查询");
        session.setCreatedAt(OffsetDateTime.now().minusHours(1));
        session.setUpdatedAt(OffsetDateTime.now().minusMinutes(10));
    }

    @Test
    void listSessions_shouldRejectMissingToken() {
        assertThatThrownBy(() -> controller.listSessions(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("缺少有效的用户标识");
    }

    @Test
    void listSessions_shouldReturnSessions() {
        when(chatSessionService.listSessions("user-1")).thenReturn(List.of(session));

        ResponseEntity<List<ChatSession>> response = controller.listSessions("{\"userId\":\"user-1\"}");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(session);
    }

    @Test
    void createSession_shouldCreateWithOptionalTitle() {
        when(chatSessionService.createSession("user-1", "新建")).thenReturn(session);

        ResponseEntity<ChatSession> response = controller.createSession("{\"userId\":\"user-1\"}", Map.of("title", "新建"));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(session);
    }

    @Test
    void renameSession_shouldReturn404WhenMissing() {
        when(chatSessionService.renameSession(1L, "user-1", "新标题")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.renameSession("{\"userId\":\"user-1\"}", 1L, Map.of("title", "新标题"));

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void renameSession_shouldReturnUpdatedSession() {
        when(chatSessionService.renameSession(1L, "user-1", "新标题")).thenReturn(Optional.of(session));

        ResponseEntity<?> response = controller.renameSession("{\"userId\":\"user-1\"}", 1L, Map.of("title", "新标题"));

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(session);
    }

    @Test
    void deleteSession_shouldRejectNonAdmin() {
        when(userWhitelistService.getUserRole("user-1")).thenReturn("READER");

        ResponseEntity<?> response = controller.deleteSession("{\"userId\":\"user-1\"}", 1L);

        assertThat(response.getStatusCodeValue()).isEqualTo(403);
        verify(chatSessionService, never()).archiveSession(any(), any());
    }

    @Test
    void deleteSession_shouldArchiveWhenAdmin() {
        when(userWhitelistService.getUserRole("user-1")).thenReturn("ADMIN");
        when(chatSessionService.archiveSession(1L, "user-1")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteSession("{\"userId\":\"user-1\"}", 1L);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "会话已删除"));
    }

    @Test
    void deleteSession_shouldReturn404WhenSessionMissing() {
        when(userWhitelistService.getUserRole("user-1")).thenReturn("ADMIN");
        when(chatSessionService.archiveSession(1L, "user-1")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteSession("{\"userId\":\"user-1\"}", 1L);

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void listMessages_shouldReturn404WhenSessionMissing() {
        when(chatSessionService.getByIdForUser(1L, "user-1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.listMessages("{\"userId\":\"user-1\"}", 1L);

        assertThat(response.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void listMessages_shouldReturnMessages() {
        when(chatSessionService.getByIdForUser(1L, "user-1")).thenReturn(Optional.of(session));
        when(chatMessageService.listMessages(session)).thenReturn(List.of(new ChatMessage()));

        ResponseEntity<?> response = controller.listMessages("{\"userId\":\"user-1\"}", 1L);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(List.class);
    }
}
