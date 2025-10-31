package com.chatbi.interceptor;

import com.chatbi.annotation.EnableAuth;
import com.chatbi.model.UserToken;
import com.chatbi.service.UserWhitelistService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenInterceptorTest {

    @Mock
    private UserWhitelistService userWhitelistService;

    @InjectMocks
    private TokenInterceptor interceptor;

    private HandlerMethod securedHandler;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        Method method = SampleController.class.getMethod("securedEndpoint");
        securedHandler = new HandlerMethod(new SampleController(), method);
    }

    @Test
    void preHandle_shouldRejectMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, securedHandler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(userWhitelistService, never()).isWhitelisted(anyString());
    }

    @Test
    void preHandle_shouldRejectInvalidTokenFormat() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Login-Token", "invalid-format");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, securedHandler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(userWhitelistService, never()).isWhitelisted(anyString());
    }

    @Test
    void preHandle_shouldRejectWhenNotWhitelisted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Login-Token", "{\"userId\":\"user-1\",\"roleNames\":[\"ADMIN\"]}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userWhitelistService.isWhitelisted("user-1")).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, securedHandler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void preHandle_shouldRejectWhenRolesMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Login-Token", "{\"userId\":\"user-1\",\"roleNames\":[\"USER\"]}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userWhitelistService.isWhitelisted("user-1")).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, securedHandler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void preHandle_shouldAllowWhenTokenValidAndAuthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secure");
        request.addHeader("Login-Token", "{\"userId\":\"user-1\",\"roleNames\":[\"ADMIN\",\"USER\"]}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userWhitelistService.isWhitelisted("user-1")).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, securedHandler);

        assertThat(result).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void parseUserTokenFromJson_shouldSupportBase64Payload() {
        String json = "{\"userId\":\"u-1\",\"roleNames\":[\"ADMIN\"]}";
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        UserToken token = TokenInterceptor.parseUserTokenFromJson(base64);

        assertThat(token).isNotNull();
        assertThat(token.getUserId()).isEqualTo("u-1");
        assertThat(token.getRoleNames()).contains("ADMIN");
    }

    static class SampleController {

        @EnableAuth(roleNames = {"ADMIN"})
        public void securedEndpoint() {
        }
    }
}
