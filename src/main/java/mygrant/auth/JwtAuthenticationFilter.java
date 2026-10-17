package mygrant.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mygrant.user.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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

            var user = subject == null ? java.util.Optional.<mygrant.user.User>empty()
                    : userRepository.findByEmail(subject);
            if (subject != null
                    && SecurityContextHolder.getContext().getAuthentication() == null
                    && user.isPresent()
                    && jwtService.isTokenValid(token, user.get().getEmail())) {
                if (!user.get().isProfileComplete()
                        && !request.getRequestURI().equals("/api/v1/profiles/me")) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Complete your profile before using this feature");
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(subject, null, java.util.Collections.emptyList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (RuntimeException ignored) {
            // Invalid tokens remain unauthenticated and are rejected by Spring Security.
        }

        filterChain.doFilter(request, response);
    }
}
