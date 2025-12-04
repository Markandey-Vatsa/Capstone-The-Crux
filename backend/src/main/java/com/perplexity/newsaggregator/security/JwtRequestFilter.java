package com.perplexity.newsaggregator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    
    @Autowired
    private JwtUtil jwtUtil;
    
    @Autowired(required = false)
    private UserDetailsService userDetailsService; // will be present once we define it
    
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, 
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        
    final String authorizationHeader = request.getHeader("Authorization");
    logger.debug("JWT Filter - Incoming " + request.getMethod() + " " + request.getRequestURI() + " AuthHeader=" + authorizationHeader);
        
        String username = null;
        String jwt = null;
        
        // Extract JWT from Authorization header
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                username = jwtUtil.extractUsername(jwt);
                logger.debug("JWT Filter - Extracted username '" + username + "' from token");
            } catch (Exception e) {
                logger.error("JWT Filter - Token extraction failed: " + e.getMessage(), e);
            }
        }
        
        // Validate JWT and set authentication context
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            logger.debug("JWT Filter - SecurityContext empty, proceeding with validation for '" + username + "'");
            try {
                UserDetails userDetails = null;
                if (userDetailsService != null) {
                    userDetails = userDetailsService.loadUserByUsername(username);
                    logger.debug("JWT Filter - Loaded UserDetails username='" + userDetails.getUsername() + "' enabled=" + userDetails.isEnabled());
                } else {
                    logger.warn("JWT Filter - No UserDetailsService bean available; using bare username authentication");
                }
                boolean valid = jwtUtil.validateToken(jwt, username);
                logger.debug("JWT Filter - Token validation result for '" + username + "': " + valid);
                if (valid) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails != null ? userDetails : username,
                            null,
                            userDetails != null ? userDetails.getAuthorities() : new ArrayList<>());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    logger.debug("JWT Filter - Authentication set in context for '" + username + "'");
                } else {
                    logger.warn("JWT Filter - Token failed validation for '" + username + "'");
                }
            } catch (Exception ex) {
                logger.error("JWT Filter - Exception during validation: " + ex.getMessage(), ex);
            }
        }
        
        chain.doFilter(request, response);
    }
}
