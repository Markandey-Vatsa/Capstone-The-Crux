package com.perplexity.newsaggregator.repository;

import com.perplexity.newsaggregator.entity.ReadingList;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingListRepository extends MongoRepository<ReadingList, String> {
    
    /**
     * Find all reading lists for a specific user, ordered by displayOrder
     */
    List<ReadingList> findByUserIdOrderByDisplayOrderAsc(String userId);
    
    /**
     * Find a reading list by ID and user ID (for ownership verification)
     */
    Optional<ReadingList> findByIdAndUserId(String id, String userId);
    
    /**
     * Find reading lists by user ID and name (for duplicate name checking)
     */
    Optional<ReadingList> findByUserIdAndName(String userId, String name);
    
    /**
     * Count reading lists for a specific user
     */
    long countByUserId(String userId);
    
    /**
     * Find reading lists that contain a specific article
     */
    @Query("{ 'userId': ?0, 'articleIds': ?1 }")
    List<ReadingList> findByUserIdAndArticleIdsContaining(String userId, String articleId);
    
    /**
     * Delete all reading lists for a user (for cleanup operations)
     */
    void deleteByUserId(String userId);
    
    /**
     * Find a reading list by share token (for public access)
     */
    Optional<ReadingList> findByShareToken(String shareToken);
    
    /**
     * Find public reading lists by share token
     */
    @Query("{ 'shareToken': ?0, 'isPublic': true }")
    Optional<ReadingList> findByShareTokenAndIsPublic(String shareToken);
}