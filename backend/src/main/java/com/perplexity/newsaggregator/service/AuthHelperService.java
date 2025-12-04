package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthHelperService {
    @Autowired
    private UserRepository userRepository;

    public User getUserFromAuthentication(Authentication authentication) {
        if (authentication == null) {
            throw new BadCredentialsException("User is not authenticated");
        }
        String username = authentication.getName();
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BadCredentialsException("User not found for username: " + username));
    }
}
