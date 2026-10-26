package mygrant.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mygrant.common.ProfileIncompleteException;
import mygrant.user.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** The only paths a signed-in user can call before finishing onboarding. */
    static final Set<String> ONBOARDING_PATHS = Set.of("/api/v1/profiles/me", "/api/v1/users/me",
            "/api/v1/auth/logout", "/api/v1/account/deletion-request", "/api/v1/account/deletion-confirm");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SecurityErrorHandler securityErrors;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.securityErrors = new SecurityErrorHandler(exceptionResolver);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);

        try {
            String subject = jwtService.extractSubject(token);
            String tokenRole = jwtService.extractRole(token);

            var user = subject == null ? java.util.Optional.<mygrant.user.User>empty()
                    : userRepository.findByEmail(subject);
            if (subject != null
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && user.isPresent()
                    && user.get().isActive()
                    && jwtService.isAccessTokenValid(token, user.get().getEmail())
                    && tokenRole != null
                    && tokenRole.equals(user.get().getRole().name())) {
                if (!user.get().isProfileComplete()
                        && !ONBOARDING_PATHS.contains(request.getRequestURI())) {
                    securityErrors.handle(request, response, new ProfileIncompleteException());
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(subject, null,
                                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + tokenRole)));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (RuntimeException ignored) {
            // Invalid tokens remain unauthenticated and are rejected by Spring Security.
        }

        filterChain.doFilter(request, response);
    }
}
