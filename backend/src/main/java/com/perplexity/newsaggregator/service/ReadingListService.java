package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.ReadingList;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.repository.ReadingListRepository;
import com.perplexity.newsaggregator.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ReadingListService {
    
    private static final Logger logger = LoggerFactory.getLogger(ReadingListService.class);
    
    @Autowired
    private ReadingListRepository readingListRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private ArticleRepository articleRepository;
    
    /**
     * Get all reading lists for a user, ordered by displayOrder
     */
    public List<ReadingList> getUserReadingLists(String userId) {
        logger.debug("Getting reading lists for user: {}", userId);
        return readingListRepository.findByUserIdOrderByDisplayOrderAsc(userId);
    }
    
    /**
     * Create a new reading list for a user
     */
    public ReadingList createReadingList(String userId, String name, String colorTheme) {
        logger.debug("Creating reading list '{}' for user: {}", name, userId);
        
        // Check for duplicate name
        Optional<ReadingList> existing = readingListRepository.findByUserIdAndName(userId, name);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("A reading list with this name already exists");
        }
        
        // Get next display order
        List<ReadingList> userLists = readingListRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        int nextOrder = userLists.size();
        
        // Create new reading list
        ReadingList readingList = new ReadingList(userId, name, colorTheme, nextOrder);
        ReadingList saved = readingListRepository.save(readingList);
        
        // Update user's readingListIds
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.getReadingListIds().add(saved.getId());
            userRepository.save(user);
        }
        
        logger.info("Created reading list '{}' with ID: {} for user: {}", name, saved.getId(), userId);
        return saved;
    }
    
    /**
     * Update a reading list (name and/or color theme)
     */
    public ReadingList updateReadingList(String userId, String listId, String name, String colorTheme) {
        logger.debug("Updating reading list {} for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Check for duplicate name if name is being changed
        if (name != null && !name.equals(readingList.getName())) {
            Optional<ReadingList> existing = readingListRepository.findByUserIdAndName(userId, name);
            if (existing.isPresent() && !existing.get().getId().equals(listId)) {
                throw new IllegalArgumentException("A reading list with this name already exists");
            }
            readingList.setName(name);
        }
        
        if (colorTheme != null) {
            readingList.setColorTheme(colorTheme);
        }
        
        readingList.touch();
        ReadingList updated = readingListRepository.save(readingList);
        
        logger.info("Updated reading list: {}", listId);
        return updated;
    }
    
    /**
     * Delete a reading list
     */
    public void deleteReadingList(String userId, String listId) {
        logger.debug("Deleting reading list {} for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Remove from user's readingListIds
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.getReadingListIds().remove(listId);
            userRepository.save(user);
        }
        
        // Delete the reading list
        readingListRepository.delete(readingList);
        
        logger.info("Deleted reading list: {}", listId);
    }
    
    /**
     * Add an article to a reading list
     */
    public ReadingList addArticleToList(String userId, String listId, String articleId) {
        logger.debug("Adding article {} to reading list {} for user: {}", articleId, listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Check if article exists
        Optional<Article> articleOpt = articleRepository.findById(articleId);
        if (!articleOpt.isPresent()) {
            throw new IllegalArgumentException("Article not found");
        }
        
        // Check if article is already in the list
        if (readingList.getArticleIds().contains(articleId)) {
            throw new IllegalArgumentException("Article is already in this reading list");
        }
        
        // Add article to list
        readingList.getArticleIds().add(articleId);
        readingList.touch();
        ReadingList updated = readingListRepository.save(readingList);
        
        logger.info("Added article {} to reading list: {}", articleId, listId);
        return updated;
    }
    
    /**
     * Remove an article from a reading list
     */
    public ReadingList removeArticleFromList(String userId, String listId, String articleId) {
        logger.debug("Removing article {} from reading list {} for user: {}", articleId, listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Remove article from list
        boolean removed = readingList.getArticleIds().remove(articleId);
        if (!removed) {
            throw new IllegalArgumentException("Article is not in this reading list");
        }
        
        readingList.touch();
        ReadingList updated = readingListRepository.save(readingList);
        
        logger.info("Removed article {} from reading list: {}", articleId, listId);
        return updated;
    }
    
    /**
     * Get articles in a reading list
     */
    public List<Article> getReadingListArticles(String userId, String listId) {
        logger.debug("Getting articles for reading list {} for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Get articles by IDs, maintaining order
        List<Article> articles = new ArrayList<>();
        for (String articleId : readingList.getArticleIds()) {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isPresent()) {
                articles.add(articleOpt.get());
            } else {
                logger.warn("Article {} not found, removing from reading list {}", articleId, listId);
                // Remove missing article from list
                readingList.getArticleIds().remove(articleId);
                readingList.touch();
                readingListRepository.save(readingList);
            }
        }
        
        return articles;
    }
    
    /**
     * Reorder reading lists for a user
     */
    public void reorderReadingLists(String userId, List<String> orderedListIds) {
        logger.debug("Reordering reading lists for user: {}", userId);
        
        List<ReadingList> userLists = readingListRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        
        // Validate that all provided IDs belong to the user
        for (String listId : orderedListIds) {
            boolean found = userLists.stream().anyMatch(list -> list.getId().equals(listId));
            if (!found) {
                throw new IllegalArgumentException("Reading list not found or not owned by user: " + listId);
            }
        }
        
        // Update display order
        for (int i = 0; i < orderedListIds.size(); i++) {
            String listId = orderedListIds.get(i);
            ReadingList readingList = userLists.stream()
                .filter(list -> list.getId().equals(listId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reading list not found: " + listId));
            
            readingList.setDisplayOrder(i);
            readingList.touch();
            readingListRepository.save(readingList);
        }
        
        logger.info("Reordered {} reading lists for user: {}", orderedListIds.size(), userId);
    }
    
    /**
     * Get reading lists that contain a specific article
     */
    public List<ReadingList> getListsContainingArticle(String userId, String articleId) {
        return readingListRepository.findByUserIdAndArticleIdsContaining(userId, articleId);
    }

    /**
     * Reorder articles within a reading list
     */
    public ReadingList reorderArticlesInList(String userId, String listId, List<String> orderedArticleIds) {
        logger.debug("Reordering articles in reading list {} for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Validate that all provided article IDs are in the current list
        List<String> currentArticleIds = readingList.getArticleIds();
        for (String articleId : orderedArticleIds) {
            if (!currentArticleIds.contains(articleId)) {
                throw new IllegalArgumentException("Article not found in reading list: " + articleId);
            }
        }
        
        // Validate that we have all articles (no missing ones)
        if (orderedArticleIds.size() != currentArticleIds.size()) {
            throw new IllegalArgumentException("Article count mismatch. Expected " + currentArticleIds.size() + " articles, got " + orderedArticleIds.size());
        }
        
        // Update the article order
        readingList.setArticleIds(new ArrayList<>(orderedArticleIds));
        readingList.touch();
        ReadingList updated = readingListRepository.save(readingList);
        
        logger.info("Reordered {} articles in reading list: {}", orderedArticleIds.size(), listId);
        return updated;
    }
    
    /**
     * Generate share token and make reading list public
     */
    public String generateShareToken(String userId, String listId) {
        logger.debug("Generating share token for reading list {} for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        
        // Generate new token if none exists, or reuse existing token
        String shareToken = readingList.getShareToken();
        if (shareToken == null || shareToken.trim().isEmpty()) {
            shareToken = java.util.UUID.randomUUID().toString();
        }
        
        // Make list public with the token
        readingList.makePublic(shareToken);
        readingListRepository.save(readingList);
        
        logger.info("Generated share token for reading list: {}", listId);
        return shareToken;
    }
    
    /**
     * Make reading list public (reuse existing token if available)
     */
    public ReadingList makeListPublic(String userId, String listId) {
        logger.debug("Making reading list {} public for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        logger.debug("Before making public - isPublic: {}, shareToken: {}", readingList.isPublic(), readingList.getShareToken());
        
        // Generate token if none exists
        if (readingList.getShareToken() == null || readingList.getShareToken().trim().isEmpty()) {
            String shareToken = java.util.UUID.randomUUID().toString();
            logger.debug("Generated new share token: {}", shareToken);
            readingList.makePublic(shareToken);
        } else {
            // Reuse existing token
            logger.debug("Reusing existing share token: {}", readingList.getShareToken());
            readingList.makePublic(readingList.getShareToken());
        }
        
        logger.debug("After making public - isPublic: {}, shareToken: {}", readingList.isPublic(), readingList.getShareToken());
        ReadingList updated = readingListRepository.save(readingList);
        logger.debug("After saving - isPublic: {}, shareToken: {}", updated.isPublic(), updated.getShareToken());
        logger.info("Made reading list public: {}", listId);
        return updated;
    }
    
    /**
     * Make reading list private
     */
    public ReadingList makeListPrivate(String userId, String listId) {
        logger.debug("Making reading list {} private for user: {}", listId, userId);
        
        ReadingList readingList = getReadingListByIdAndUserId(listId, userId);
        logger.debug("Before making private - isPublic: {}, shareToken: {}", readingList.isPublic(), readingList.getShareToken());
        
        readingList.makePrivate();
        logger.debug("After making private - isPublic: {}, shareToken: {}", readingList.isPublic(), readingList.getShareToken());
        
        ReadingList updated = readingListRepository.save(readingList);
        logger.debug("After saving - isPublic: {}, shareToken: {}", updated.isPublic(), updated.getShareToken());
        logger.info("Made reading list private: {}", listId);
        return updated;
    }
    
    /**
     * Find reading list by share token (for public access)
     */
    public ReadingList findByShareToken(String shareToken) {
        logger.debug("Finding reading list by share token: {}", shareToken);
        
        // Validate share token format
        if (!isValidShareToken(shareToken)) {
            logger.warn("Invalid share token format provided: {}", shareToken);
            return null;
        }
        
        Optional<ReadingList> readingListOpt = readingListRepository.findByShareTokenAndIsPublic(shareToken);
        if (readingListOpt.isPresent()) {
            logger.debug("Found public reading list for share token");
            return readingListOpt.get();
        } else {
            logger.warn("No public reading list found for share token: {}", shareToken);
            return null;
        }
    }
    
    /**
     * Check if a reading list is publicly accessible
     */
    public boolean isListPublic(String shareToken) {
        if (!isValidShareToken(shareToken)) {
            return false;
        }
        
        ReadingList readingList = findByShareToken(shareToken);
        return readingList != null && readingList.isPublic();
    }
    
    /**
     * Validate share token format (supports current UUID tokens and legacy 24-char hex tokens)
     */
    private boolean isValidShareToken(String shareToken) {
        if (shareToken == null) {
            return false;
        }

        String trimmedToken = shareToken.trim();
        if (trimmedToken.isEmpty()) {
            return false;
        }

        try {
            // Primary format: UUID (current standard)
            java.util.UUID.fromString(trimmedToken);
            return true;
        } catch (IllegalArgumentException ignored) {
            // Legacy format: Mongo-style ObjectId (24 hex chars)
            return trimmedToken.matches("^[a-fA-F0-9]{24}$");
        }
    }
    
    /**
     * Get public reading list with articles (for public API)
     */
    public ReadingList getPublicReadingListWithArticles(String shareToken) {
        logger.debug("Getting public reading list with articles for share token");
        
        ReadingList readingList = findByShareToken(shareToken);
        if (readingList == null) {
            logger.warn("Public reading list not found for share token");
            return null;
        }
        
        // The reading list already contains article IDs
        // Articles will be fetched separately by the controller if needed
        return readingList;
    }
    
    /**
     * Helper method to get reading list by ID and verify ownership
     */
    private ReadingList getReadingListByIdAndUserId(String listId, String userId) {
        Optional<ReadingList> readingListOpt = readingListRepository.findByIdAndUserId(listId, userId);
        if (!readingListOpt.isPresent()) {
            throw new IllegalArgumentException("Reading list not found or not owned by user");
        }
        return readingListOpt.get();
    }
}