package com.chatbi.service;

import com.chatbi.model.DatabaseConnection;
import com.chatbi.model.DatabaseConnectionCreate;
import com.chatbi.model.DatabaseConnectionUpdate;
import com.chatbi.repository.DatabaseConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseConnectionServiceTest {

    @Mock
    private DatabaseConnectionRepository repository;

    @InjectMocks
    private DatabaseConnectionService service;

    private final AtomicReference<DatabaseConnection> savedDefault = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "datasourceUrl", "jdbc:mysql://localhost:3306/main_db?useSSL=false");
        ReflectionTestUtils.setField(service, "datasourceUsername", "root");
        ReflectionTestUtils.setField(service, "datasourcePassword", "secret");

        when(repository.save(any(DatabaseConnection.class))).thenAnswer(invocation -> {
            DatabaseConnection value = invocation.getArgument(0);
            savedDefault.set(value);
            return value;
        });
    }

    @Test
    void createConnection_shouldPersistEntity() {
        DatabaseConnectionCreate create = new DatabaseConnectionCreate(
            "Analytics",
            "db.local",
            3307,
            "app",
            "pass",
            "analytics",
            "utf8mb4",
            "desc"
        );

        DatabaseConnection saved = service.createConnection(create);

        assertThat(saved.getName()).isEqualTo("Analytics");
        assertThat(saved.getHost()).isEqualTo("db.local");
        assertThat(saved.getPort()).isEqualTo(3307);
        verify(repository, times(1)).save(any(DatabaseConnection.class));
    }

    @Test
    void getAllConnections_shouldCreateDefaultWhenEmpty() {
        when(repository.findAll()).thenAnswer(invocation -> {
            if (savedDefault.get() == null) {
                return new ArrayList<>();
            }
            return List.of(savedDefault.get());
        });

        List<DatabaseConnection> connections = service.getAllConnections();

        assertThat(connections).hasSize(1);
        DatabaseConnection defaultConn = connections.getFirst();
        assertThat(defaultConn.getName()).isEqualTo("默认数据库");
        assertThat(defaultConn.getHost()).isEqualTo("localhost");
        verify(repository, times(1)).save(any(DatabaseConnection.class));
    }

    @Test
    void getActiveConnection_shouldReturnExistingWhenPresent() {
        DatabaseConnection existing = new DatabaseConnection(
            "id-1",
            "Prod",
            "prod.host",
            3306,
            "user",
            "pwd",
            "prod_db",
            "utf8",
            "",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()
        );

        when(repository.findFirstByIsActiveTrue()).thenReturn(Optional.of(existing));

        DatabaseConnection result = service.getActiveConnection();

        assertThat(result).isEqualTo(existing);
        verify(repository, never()).save(any(DatabaseConnection.class));
    }

    @Test
    void getActiveConnection_shouldCreateDefaultWhenMissing() {
        when(repository.findFirstByIsActiveTrue()).thenAnswer(invocation -> {
            if (savedDefault.get() == null) {
                return Optional.empty();
            }
            return Optional.of(savedDefault.get());
        });

        DatabaseConnection result = service.getActiveConnection();

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("默认数据库");
        verify(repository, times(1)).save(any(DatabaseConnection.class));
    }

    @Test
    void updateConnection_shouldApplyNonNullFields() {
        DatabaseConnection original = new DatabaseConnection(
            "conn-1",
            "Source",
            "host",
            3306,
            "user",
            "pwd",
            "db",
            "utf8",
            "old",
            true,
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().minusDays(1)
        );

        when(repository.findById("conn-1")).thenReturn(Optional.of(original));
        when(repository.save(any(DatabaseConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        DatabaseConnectionUpdate update = new DatabaseConnectionUpdate();
        update.setName("Updated");
        update.setHost("new.host");
        update.setPort(3310);
        update.setPassword("newPwd");
        update.setIsActive(false);

        DatabaseConnection updated = service.updateConnection("conn-1", update);

        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getHost()).isEqualTo("new.host");
        assertThat(updated.getPort()).isEqualTo(3310);
        assertThat(updated.getPassword()).isEqualTo("newPwd");
        assertThat(updated.getIsActive()).isFalse();
    }

    @Test
    void updateConnection_shouldReturnNullWhenMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        DatabaseConnectionUpdate update = new DatabaseConnectionUpdate();
        update.setName("none");

        DatabaseConnection result = service.updateConnection("missing", update);

        assertThat(result).isNull();
        verify(repository, never()).save(any(DatabaseConnection.class));
    }

    @Test
    void deleteConnection_shouldPreventRemovingLastOrDefault() {
        DatabaseConnection only = buildConnection("only", "Only");
        when(repository.findAll()).thenReturn(List.of(only));

        assertThat(service.deleteConnection("only"))
            .as("cannot delete last connection")
            .isFalse();

        DatabaseConnection other = buildConnection("other", "Other");
        when(repository.findAll()).thenReturn(List.of(only, other));
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.deleteConnection("missing"))
            .as("connection must exist")
            .isFalse();

        DatabaseConnection defaultConn = new DatabaseConnection(
            "default",
            "默认数据库",
            "localhost",
            3306,
            "root",
            "secret",
            "main_db",
            "utf8mb4",
            "",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        when(repository.findById("default")).thenReturn(Optional.of(defaultConn));

        assertThat(service.deleteConnection("default"))
            .as("cannot delete default connection")
            .isFalse();
        verify(repository, never()).deleteById("default");
    }

    @Test
    void deleteConnection_shouldDeleteWhenValid() {
        DatabaseConnection one = buildConnection("one", "One");
        DatabaseConnection two = buildConnection("two", "Two");

        when(repository.findAll()).thenReturn(List.of(one, two));
        when(repository.findById("two")).thenReturn(Optional.of(two));

        assertThat(service.deleteConnection("two")).isTrue();
        verify(repository).deleteById("two");
    }

    private static DatabaseConnection buildConnection(String id, String name) {
        return new DatabaseConnection(
            id,
            name,
            "host",
            3306,
            "user",
            "pwd",
            "db",
            "utf8",
            "",
            true,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }
}
