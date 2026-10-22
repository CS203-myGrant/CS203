package mygrant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

class JwtAuthenticationFilterTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAValidAccessTokenWithoutConsultingRefreshTokenState()
            throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRepository userRepository = mock(UserRepository.class);
        FilterChain filterChain = mock(FilterChain.class);

        User user = new User();
        user.setEmail("student@example.com");
        user.setRole(UserRole.APPLICANT);

        when(jwtService.extractSubject("access-token"))
                .thenReturn("student@example.com");
        when(jwtService.extractRole("access-token"))
                .thenReturn("APPLICANT");
        when(jwtService.isAccessTokenValid(
                "access-token",
                "student@example.com"
        )).thenReturn(true);

        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user));

        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtService, userRepository);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/profiles/me");
        request.addHeader("Authorization", "Bearer access-token");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                filterChain
        );

        assertThat(SecurityContextHolder.getContext()
                .getAuthentication()
                .getName()).isEqualTo("student@example.com");

        verify(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}