package com.physiomotionplus.physiomotionplusadminapi.security.firebase;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

public class FirebaseAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);
    private final FirebaseAuth firebaseAuth;
    private final AuthenticationEntryPoint entryPoint;

    public FirebaseAuthFilter(FirebaseAuth firebaseAuth, AuthenticationEntryPoint entryPoint) {
        this.firebaseAuth = firebaseAuth;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.substring(7).isBlank()) {
            entryPoint.commence(request, response, new InsufficientAuthenticationException("Bearer token is required"));
            return;
        }
        FirebaseToken decoded;
        try {
            decoded = firebaseAuth.verifyIdToken(header.substring(7).trim(), true);
        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
            log.warn("Admin Firebase ID token validation failed: {}", exception.getMessage());
            entryPoint.commence(request, response, new BadCredentialsException("Invalid Firebase token", exception));
            return;
        }
            var principal = new FirebasePrincipal(decoded.getUid(), decoded.getEmail(), decoded.getName(), decoded.getClaims(), decoded);
            var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_PHYSIO")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
    }
}
