package com.chatbi.service.impl;

import com.chatbi.model.UserWhitelist;
import com.chatbi.repository.UserWhitelistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserWhitelistServiceImplTest {

    @Mock
    private UserWhitelistRepository repository;

    @InjectMocks
    private UserWhitelistServiceImpl service;

    @Test
    void isWhitelisted_shouldReturnTrueWhenActive() {
        when(repository.existsByUserIdAndIsActiveTrue("user-1")).thenReturn(true);

        assertThat(service.isWhitelisted("user-1")).isTrue();
    }

    @Test
    void isWhitelisted_shouldReturnFalseForBlankOrMissing() {
        assertThat(service.isWhitelisted(null)).isFalse();
        assertThat(service.isWhitelisted("   ")).isFalse();

        when(repository.existsByUserIdAndIsActiveTrue("user-2")).thenReturn(false);
        assertThat(service.isWhitelisted("user-2")).isFalse();
    }

    @Test
    void getUserRole_shouldReturnRoleWhenWhitelisted() {
        UserWhitelist whitelist = new UserWhitelist();
        whitelist.setUserId("user-1");
        whitelist.setRole("ADMIN");

        when(repository.findByUserIdAndIsActiveTrue("user-1")).thenReturn(Optional.of(whitelist));

        assertThat(service.getUserRole("user-1")).isEqualTo("ADMIN");
    }

    @Test
    void getUserRole_shouldReturnNullWhenMissing() {
        assertThat(service.getUserRole(null)).isNull();
        when(repository.findByUserIdAndIsActiveTrue("user-2")).thenReturn(Optional.empty());

        assertThat(service.getUserRole("user-2")).isNull();
    }
}
