package com.perplexity.newsaggregator.repository;

import com.perplexity.newsaggregator.entity.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    // Find user by username for authentication
    Optional<User> findByUsername(String username);
    
    // Find user by email
    Optional<User> findByEmail(String email);
    
    // Check if username already exists during registration
    boolean existsByUsername(String username);
    
    // Check if email already exists during registration
    boolean existsByEmail(String email);
}
