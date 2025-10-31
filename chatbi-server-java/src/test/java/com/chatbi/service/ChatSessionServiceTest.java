package com.chatbi.service;

import com.chatbi.model.ChatSession;
import com.chatbi.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    private ChatSessionRepository repository;

    @InjectMocks
    private ChatSessionService service;

    private ChatSession session;

    @BeforeEach
    void setUp() {
        session = new ChatSession();
        session.setId(1L);
        session.setUserId("user-1");
        session.setTitle("历史查询");
        session.setArchived(false);
        session.setCreatedAt(OffsetDateTime.now().minusDays(1));
        session.setUpdatedAt(OffsetDateTime.now().minusDays(1));
    }

    @Test
    void listSessions_shouldReturnOrderedSessions() {
        when(repository.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc("user-1"))
            .thenReturn(List.of(session));

        assertThat(service.listSessions("user-1")).containsExactly(session);
    }

    @Test
    void createSession_shouldPopulateDefaults() {
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        when(repository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession created = service.createSession("user-2", "新建会话");

        verify(repository).save(captor.capture());
        ChatSession saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo("user-2");
        assertThat(saved.getTitle()).isEqualTo("新建会话");
        assertThat(saved.getArchived()).isFalse();
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    void renameSession_shouldUpdateTitleWhenPresent() {
        when(repository.findByIdAndUserId(1L, "user-1")).thenReturn(Optional.of(session));
        when(repository.save(any(ChatSession.class))).thenReturn(session);

        Optional<ChatSession> result = service.renameSession(1L, "user-1", "新标题");

        assertThat(result).isPresent();
        assertThat(session.getTitle()).isEqualTo("新标题");
        verify(repository).save(session);
    }

    @Test
    void archiveSession_shouldToggleFlag() {
        when(repository.findByIdAndUserId(1L, "user-1")).thenReturn(Optional.of(session));
        when(repository.save(any(ChatSession.class))).thenReturn(session);

        boolean archived = service.archiveSession(1L, "user-1");

        assertThat(archived).isTrue();
        assertThat(session.getArchived()).isTrue();
        verify(repository).save(session);
    }

    @Test
    void touchUpdatedAt_shouldPersistTimestamp() {
        when(repository.save(session)).thenReturn(session);

        OffsetDateTime previous = session.getUpdatedAt();
        service.touchUpdatedAt(session);

        assertThat(session.getUpdatedAt()).isAfter(previous);
        verify(repository, times(1)).save(session);
    }
}
