package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.dto.AuthResponse;
import com.perplexity.newsaggregator.dto.LoginRequest;
import com.perplexity.newsaggregator.dto.RegisterRequest;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.UserRepository;
import com.perplexity.newsaggregator.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Autowired
    private JwtUtil jwtUtil;
    
    // User registration endpoint
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            System.out.println("Registration attempt for username: " + request.getUsername());
            
            // Check if username already exists
            if (userRepository.existsByUsername(request.getUsername())) {
                System.out.println("Username already exists: " + request.getUsername());
                return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, "Username already exists"));
            }
            
            // Check if email already exists
            if (userRepository.existsByEmail(request.getEmail())) {
                System.out.println("Email already exists: " + request.getEmail());
                return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, "Email already exists"));
            }
            
            // Create new user with encoded password
            User user = new User();
            user.setUsername(request.getUsername());
            user.setEmail(request.getEmail());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            
            // Save user to database
            userRepository.save(user);
            System.out.println("User registered successfully: " + user.getUsername());
            
            return ResponseEntity.ok(new AuthResponse(null, user.getUsername(), "User registered successfully"));
            
        } catch (Exception e) {
            System.err.println("Registration error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(new AuthResponse(null, null, "Registration failed: " + e.getMessage()));
        }
    }
    
    // User login endpoint
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            System.out.println("Login attempt for username: " + request.getUsername());
            
            // Find user by username
            Optional<User> userOptional = userRepository.findByUsername(request.getUsername());
            
            if (userOptional.isEmpty()) {
                System.out.println("User not found: " + request.getUsername());
                return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, "Invalid username or password"));
            }
            
            User user = userOptional.get();
            
            // Verify password
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                System.out.println("Invalid password for user: " + request.getUsername());
                return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, "Invalid username or password"));
            }
            
            // Generate JWT token
            String token = jwtUtil.generateToken(user.getUsername());
            System.out.println("Login successful for user: " + user.getUsername());
            
            return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), "Login successful"));
            
        } catch (Exception e) {
            System.err.println("Login error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(new AuthResponse(null, null, "Login failed: " + e.getMessage()));
        }
    }
    
    // Health check endpoint
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Auth service is running");
    }
}
