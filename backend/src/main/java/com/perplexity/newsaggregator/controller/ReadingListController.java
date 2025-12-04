package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.dto.*;
import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.ReadingList;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.service.AuthHelperService;
import com.perplexity.newsaggregator.service.ReadingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reading-lists")
public class ReadingListController {

    private static final Logger logger = LoggerFactory.getLogger(ReadingListController.class);

    @Autowired
    private ReadingListService readingListService;

    @Autowired
    private AuthHelperService authHelperService;

    /**
     * Get all reading lists for the authenticated user
     */
    @GetMapping
    public ResponseEntity<?> getUserReadingLists(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Fetching reading lists for user: {}", username);
            List<ReadingList> readingLists = readingListService.getUserReadingLists(userId);
            
            // Convert to DTOs
            List<ReadingListSummaryDTO> summaries = readingLists.stream()
                .map(this::convertToSummaryDTO)
                .collect(Collectors.toList());

            return ResponseEntity.ok(summaries);
        } catch (BadCredentialsException e) {
            logger.warn("Reading lists authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error retrieving reading lists: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load reading lists"));
        }
    }

    /**
     * Create a new reading list
     */
    @PostMapping
    public ResponseEntity<?> createReadingList(@Valid @RequestBody CreateReadingListRequest request, 
                                             Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Creating reading list '{}' for user: {}", request.getName(), username);
            
            // Set default color theme if not provided
            String colorTheme = request.getColorTheme();
            if (colorTheme == null || colorTheme.trim().isEmpty()) {
                colorTheme = "#3b82f6"; // Default blue
            }

            ReadingList readingList = readingListService.createReadingList(userId, request.getName(), colorTheme);
            ReadingListSummaryDTO summary = convertToSummaryDTO(readingList);

            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid reading list creation request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Reading list creation authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error creating reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create reading list"));
        }
    }

    /**
     * Update a reading list (name and/or color theme)
     */
    @PutMapping("/{listId}")
    public ResponseEntity<?> updateReadingList(@PathVariable String listId,
                                             @Valid @RequestBody UpdateReadingListRequest request,
                                             Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Updating reading list {} for user: {}", listId, username);

            ReadingList readingList = readingListService.updateReadingList(userId, listId, 
                request.getName(), request.getColorTheme());
            ReadingListSummaryDTO summary = convertToSummaryDTO(readingList);

            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid reading list update request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Reading list update authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error updating reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update reading list"));
        }
    }

    /**
     * Delete a reading list
     */
    @DeleteMapping("/{listId}")
    public ResponseEntity<?> deleteReadingList(@PathVariable String listId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Deleting reading list {} for user: {}", listId, username);

            readingListService.deleteReadingList(userId, listId);

            return ResponseEntity.ok(Map.of("message", "Reading list deleted successfully"));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid reading list deletion request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Reading list deletion authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error deleting reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to delete reading list"));
        }
    }

    /**
     * Reorder reading lists
     */
    @PutMapping("/reorder")
    public ResponseEntity<?> reorderReadingLists(@Valid @RequestBody ReorderReadingListsRequest request,
                                               Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Reordering reading lists for user: {}", username);

            readingListService.reorderReadingLists(userId, request.getOrderedListIds());

            return ResponseEntity.ok(Map.of("message", "Reading lists reordered successfully"));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid reading list reorder request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Reading list reorder authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error reordering reading lists: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to reorder reading lists"));
        }
    }

    /**
     * Add an article to a reading list
     */
    @PutMapping("/{listId}/articles/{articleId}")
    public ResponseEntity<?> addArticleToList(@PathVariable String listId,
                                            @PathVariable String articleId,
                                            Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Adding article {} to reading list {} for user: {}", articleId, listId, username);

            ReadingList readingList = readingListService.addArticleToList(userId, listId, articleId);
            ReadingListSummaryDTO summary = convertToSummaryDTO(readingList);

            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid add article request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Add article authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error adding article to reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to add article to reading list"));
        }
    }

    /**
     * Remove an article from a reading list
     */
    @DeleteMapping("/{listId}/articles/{articleId}")
    public ResponseEntity<?> removeArticleFromList(@PathVariable String listId,
                                                 @PathVariable String articleId,
                                                 Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Removing article {} from reading list {} for user: {}", articleId, listId, username);

            ReadingList readingList = readingListService.removeArticleFromList(userId, listId, articleId);
            ReadingListSummaryDTO summary = convertToSummaryDTO(readingList);

            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid remove article request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Remove article authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error removing article from reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to remove article from reading list"));
        }
    }

    /**
     * Reorder articles within a reading list
     */
    @PutMapping("/{listId}/articles/reorder")
    public ResponseEntity<?> reorderArticlesInList(@PathVariable String listId,
                                                 @RequestBody Map<String, Object> request,
                                                 Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            @SuppressWarnings("unchecked")
            List<String> orderedArticleIds = (List<String>) request.get("orderedArticleIds");
            if (orderedArticleIds == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "orderedArticleIds is required"));
            }

            logger.debug("Reordering articles in reading list {} for user: {}", listId, username);

            ReadingList readingList = readingListService.reorderArticlesInList(userId, listId, orderedArticleIds);
            ReadingListSummaryDTO summary = convertToSummaryDTO(readingList);

            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid reorder articles request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Reorder articles authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error reordering articles in reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to reorder articles"));
        }
    }

    /**
     * Get articles in a reading list
     */
    @GetMapping("/{listId}/articles")
    public ResponseEntity<?> getReadingListArticles(@PathVariable String listId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Getting articles for reading list {} for user: {}", listId, username);

            List<Article> articles = readingListService.getReadingListArticles(userId, listId);

            return ResponseEntity.ok(articles);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid get articles request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Get articles authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error getting reading list articles: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get reading list articles"));
        }
    }

    /**
     * Make a reading list public and generate share token
     */
    @PutMapping("/{listId}/share")
    public ResponseEntity<?> shareReadingList(@PathVariable String listId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Sharing reading list {} for user: {}", listId, username);

            ReadingList readingList = readingListService.makeListPublic(userId, listId);
            
            // Create share response with full URL
            com.perplexity.newsaggregator.dto.ShareTokenResponse shareResponse = 
                com.perplexity.newsaggregator.dto.ShareTokenResponse.create(
                    readingList.getShareToken(),
                    readingList.isPublic(),
                    readingList.getSharedAt(),
                    "http://localhost:5500" // TODO: Make this configurable
                );

            return ResponseEntity.ok(shareResponse);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid share reading list request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Share reading list authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error sharing reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to share reading list"));
        }
    }

    /**
     * Make a reading list private (unshare)
     */
    @DeleteMapping("/{listId}/share")
    public ResponseEntity<?> unshareReadingList(@PathVariable String listId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            String userId = user.getId();
            logger.debug("Unsharing reading list {} for user: {}", listId, username);

            ReadingList readingList = readingListService.makeListPrivate(userId, listId);
            
            // Create share response showing private status
            com.perplexity.newsaggregator.dto.ShareTokenResponse shareResponse = 
                new com.perplexity.newsaggregator.dto.ShareTokenResponse(
                    readingList.getShareToken(),
                    readingList.isPublic(),
                    readingList.getSharedAt()
                );

            return ResponseEntity.ok(shareResponse);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid unshare reading list request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (BadCredentialsException e) {
            logger.warn("Unshare reading list authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error unsharing reading list: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to unshare reading list"));
        }
    }

    /**
     * Convert ReadingList entity to summary DTO
     */
    private ReadingListSummaryDTO convertToSummaryDTO(ReadingList readingList) {
        return new ReadingListSummaryDTO(
            readingList.getId(),
            readingList.getName(),
            readingList.getColorTheme(),
            readingList.getDisplayOrder(),
            readingList.getArticleIds().size(),
            readingList.getCreatedAt(),
            readingList.getUpdatedAt(),
            readingList.isPublic(),
            readingList.getShareToken(),
            readingList.getSharedAt()
        );
    }
}