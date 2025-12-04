package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.repository.UserRepository;
import com.perplexity.newsaggregator.service.AuthHelperService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private com.perplexity.newsaggregator.service.NewsService newsService;

    @Autowired
    private AuthHelperService authHelperService;

    // Profile endpoint
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            logger.debug("Fetching profile for user: {}", username);
            BookmarkSnapshot snapshot = resolveBookmarks(user);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("username", user.getUsername());
            profile.put("email", user.getEmail());
            profile.put("bookmarkedCount", snapshot.count());
            profile.put("profession", user.getProfession());
            profile.put("interests", user.getInterests());
            profile.put("pinnedSources", user.getPinnedSources() != null ? user.getPinnedSources() : new ArrayList<>());
            return ResponseEntity.ok(profile);
        } catch (BadCredentialsException e) {
            logger.warn("Profile access authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error retrieving profile: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load profile"));
        }
    }

    // Update user preferences (profession + interests)
    @PutMapping("/preferences")
    public ResponseEntity<?> updatePreferences(@RequestBody Map<String, Object> body, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String profession = (String) body.getOrDefault("profession", null);
            Object interestsObj = body.get("interests");
            List<String> interests = new ArrayList<>();
            if (interestsObj instanceof List<?>) {
                for (Object o : (List<?>) interestsObj) {
                    if (o != null) interests.add(o.toString());
                }
            }
            // Normalize special interest labels to canonical domain taxonomy tokens
        List<String> normalizedInterests = interests.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
            user.setProfession(profession);
            user.setInterests(normalizedInterests);
            userRepository.save(user);
            // Invalidate cached daily briefing so next request regenerates with new preferences
            try { newsService.invalidateBriefingCache(user.getUsername()); } catch (Exception ignore) {}
            return ResponseEntity.ok(Map.of("profession", profession, "interests", normalizedInterests));
        } catch (BadCredentialsException e) {
            logger.warn("Preference update authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error updating preferences: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update preferences"));
        }
    }

    // Get user's pinned sources
    @GetMapping("/pinned-sources")
    public ResponseEntity<?> getPinnedSources(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            List<String> pinnedSources = user.getPinnedSources() != null ? user.getPinnedSources() : new ArrayList<>();
            return ResponseEntity.ok(Map.of("pinnedSources", pinnedSources));
        } catch (BadCredentialsException e) {
            logger.warn("Pinned sources authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error retrieving pinned sources: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load pinned sources"));
        }
    }

    // Update user's pinned sources (1-10 sources)
    @PutMapping("/pinned-sources")
    public ResponseEntity<?> updatePinnedSources(@RequestBody Map<String, Object> body, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            Object pinnedSourcesObj = body.get("pinnedSources");
            List<String> pinnedSources = new ArrayList<>();
            
            if (pinnedSourcesObj instanceof List<?>) {
                for (Object o : (List<?>) pinnedSourcesObj) {
                    if (o != null) pinnedSources.add(o.toString().trim());
                }
            }
            
            // Validate source count (1-10)
            if (pinnedSources.size() < 1 || pinnedSources.size() > 10) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid source count", 
                    "message", "Must select between 1 and 10 sources",
                    "code", "INVALID_SOURCE_COUNT"
                ));
            }
            
            // Validate that sources exist in database
            List<String> availableSources = newsService.getAvailableSourceNames();
            for (String source : pinnedSources) {
                if (!availableSources.contains(source)) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid source", 
                        "message", "Source '" + source + "' does not exist or has no articles",
                        "code", "INVALID_SOURCE"
                    ));
                }
            }
            
            // Remove duplicates and save
            List<String> uniquePinnedSources = pinnedSources.stream()
                .distinct()
                .collect(Collectors.toList());
            
            user.setPinnedSources(uniquePinnedSources);
            userRepository.save(user);
            
            logger.info("Updated pinned sources for user {}: {}", username, uniquePinnedSources);
            return ResponseEntity.ok(Map.of("pinnedSources", uniquePinnedSources));
            
        } catch (BadCredentialsException e) {
            logger.warn("Pinned sources update authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error updating pinned sources: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update pinned sources"));
        }
    }

    // Get all bookmarked articles for current user
    @GetMapping("/bookmarks")
    public ResponseEntity<?> getBookmarks(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            BookmarkSnapshot snapshot = resolveBookmarks(user);
            return ResponseEntity.ok(snapshot.articles());
        } catch (BadCredentialsException e) {
            logger.warn("Bookmark retrieval authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error retrieving bookmarks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load bookmarks"));
        }
    }

    // Toggle bookmark for an article
    @PostMapping("/bookmarks/{articleId}")
    public ResponseEntity<?> toggleBookmark(@PathVariable String articleId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            if (user.getSavedArticleIds() == null) {
                user.setSavedArticleIds(new ArrayList<>());
            }
            boolean added;
            if (user.getSavedArticleIds().contains(articleId)) {
                user.getSavedArticleIds().remove(articleId);
                added = false;
            } else {
                user.getSavedArticleIds().add(0, articleId); // add to front
                added = true;
            }
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("articleId", articleId, "bookmarked", added));
        } catch (BadCredentialsException e) {
            logger.warn("Bookmark toggle authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error toggling bookmark: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to toggle bookmark"));
        }
    }

    // Check bookmark status for a single article (used by frontend per-card initialization)
    @GetMapping("/bookmarks/check/{articleId}")
    public ResponseEntity<?> checkBookmark(@PathVariable String articleId, Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            List<String> ids = user.getSavedArticleIds();
            boolean bookmarked = ids != null && ids.contains(articleId);
            logger.debug("Bookmark check user='{}' article='{}' -> {}", user.getUsername(), articleId, bookmarked);
            return ResponseEntity.ok(Map.of("articleId", articleId, "bookmarked", bookmarked));
        } catch (BadCredentialsException e) {
            logger.warn("Bookmark check authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        } catch (Exception e) {
            logger.error("Error checking bookmark status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to check bookmark"));
        }
    }

    private BookmarkSnapshot resolveBookmarks(User user) {
        List<String> ids = user.getSavedArticleIds();
        if (ids == null || ids.isEmpty()) {
            return new BookmarkSnapshot(Collections.emptyList(), 0);
        }

        List<Article> articles = articleRepository.findAllById(ids);
        if (articles.isEmpty()) {
            if (!ids.isEmpty()) {
                logger.info("Pruning {} stale bookmark id(s) for user {} (no articles found)", ids.size(), user.getUsername());
                user.setSavedArticleIds(new ArrayList<>());
                userRepository.save(user);
            }
            return new BookmarkSnapshot(Collections.emptyList(), 0);
        }

        Map<String, Article> map = articles.stream().collect(Collectors.toMap(Article::getId, a -> a));
        List<Article> ordered = new ArrayList<>();
        for (String id : ids) {
            Article article = map.get(id);
            if (article != null) {
                ordered.add(article);
            }
        }

        if (ordered.size() != ids.size()) {
            logger.info("Pruning {} stale bookmark id(s) for user {}", ids.size() - ordered.size(), user.getUsername());
            List<String> updatedIds = ordered.stream()
                    .map(Article::getId)
                    .collect(Collectors.toCollection(ArrayList::new));
            user.setSavedArticleIds(updatedIds);
            userRepository.save(user);
        }

        return new BookmarkSnapshot(ordered, ordered.size());
    }

    private record BookmarkSnapshot(List<Article> articles, int count) {}
}
