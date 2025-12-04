package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.service.NewsFetchingService;
import com.perplexity.newsaggregator.service.GNewsService;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.service.AuthHelperService;
import org.springframework.security.core.Authentication;
import com.perplexity.newsaggregator.service.NewsService;
import com.perplexity.newsaggregator.service.ArticleSourceService.SourceCount;
import com.perplexity.newsaggregator.util.LoggingUtil;
// Legacy personalization utilities removed under unified taxonomy approach
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsController {
    
    private static final Logger logger = LoggerFactory.getLogger(NewsController.class);
    
    @Autowired
    private NewsFetchingService newsFetchingService;
    
    @Autowired
    private NewsService newsService;

    @Autowired
    private GNewsService gNewsService;
    
    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private AuthHelperService authHelperService;

    private Pageable getClampedPageable(int page, int size) {
        int effectiveSize = Math.min(size, 50);
        if (size > 50) {
            logger.debug("Requested size {} exceeds max limit; clamping to 50", size);
        }
        return PageRequest.of(page, effectiveSize);
    }
    
    // Get paginated news articles with optional category and source filters
    @GetMapping
    public ResponseEntity<Page<Article>> getNews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            Authentication authentication) {
        
        LoggingUtil.PerformanceTimer timer = LoggingUtil.startTimer();
        String username = authentication != null ? authentication.getName() : null;
        
        LoggingUtil.logUserAction(logger, username, "Get News", 
            "page", page, "size", size, "category", category, "source", source);
        
        try {
            // Enforce a hard maximum of 50 articles per request regardless of client-provided size
            Pageable pageable = getClampedPageable(page, size);
            Page<Article> articles;
            
            // Determine which filtering method to use based on parameters
            boolean hasCategory = category != null && !category.isEmpty() && !category.equalsIgnoreCase("All News");
            boolean hasSource = source != null && !source.isEmpty();
            // bias signal parameter removed (was unused)
        if (hasCategory && hasSource) {
        // First filter by tag, then narrow by source in-memory (rare path). Could optimize with custom query later.
        Page<Article> tagPage = newsService.getArticlesByTag(category, pageable);
        List<Article> filtered = tagPage.getContent().stream()
            .filter(a -> source.equalsIgnoreCase(a.getSource()))
            .toList();
        articles = new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
        logger.debug("Returning {} articles for tag '{}' AND source '{}' (tag-based), page {}", 
            articles.getNumberOfElements(), category, source, page);
        } else if (hasCategory) {
        // Tag-based filtering (category param treated as contextual tag)
        articles = newsService.getArticlesByTag(category, pageable);
        logger.debug("Returning {} articles for tag '{}' (tag-based), page {}", 
            articles.getNumberOfElements(), category, page);
            } else if (hasSource) {
                // Filter by source only
                articles = newsService.getArticlesBySource(source, pageable);
                logger.debug("Returning {} articles for source '{}', page {}", 
                            articles.getNumberOfElements(), source, page);
            } else {
                // No filters, return all articles
                articles = newsService.getAllArticles(pageable);
                logger.debug("Returning {} articles (no filters), page {}", 
                            articles.getNumberOfElements(), page);
            }
            
            return ResponseEntity.ok(articles);
            
        } catch (Exception e) {
            logger.error("Error fetching news articles: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // Structured Daily Briefing endpoint (returns JSON array of bullet point sentences)
    @GetMapping("/briefing")
    public ResponseEntity<Map<String,Object>> getDailyBriefing(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            java.time.LocalTime now = java.time.LocalTime.now();
            String daypart = now.isBefore(java.time.LocalTime.NOON) ? "morning" : (now.isBefore(java.time.LocalTime.of(17,0)) ? "afternoon" : "evening");
            List<String> points = newsService.buildStructuredBriefing(user);
            return ResponseEntity.ok(java.util.Map.of(
                "briefing_points", points,
                "count", points.size(),
                "daypart", daypart
            ));
        } catch (BadCredentialsException e) {
            logger.warn("Daily briefing authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).build();
            }
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to generate daily structured briefing: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", "Failed to generate briefing"));
        }
    }

    // Manual refresh: invalidate cached briefing and immediately generate a fresh one
    @PostMapping("/briefing/refresh")
    public ResponseEntity<Map<String,Object>> refreshDailyBriefing(Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            newsService.invalidateBriefingCache(username);
            java.time.LocalTime now = java.time.LocalTime.now();
            String daypart = now.isBefore(java.time.LocalTime.NOON) ? "morning" : (now.isBefore(java.time.LocalTime.of(17,0)) ? "afternoon" : "evening");
            List<String> points = newsService.buildStructuredBriefing(user);
            return ResponseEntity.ok(java.util.Map.of(
                "briefing_points", points,
                "count", points.size(),
                "daypart", daypart,
                "refreshed", true
            ));
        } catch (BadCredentialsException e) {
            logger.warn("Refresh daily briefing authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).build();
            }
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to refresh daily briefing: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(java.util.Map.of("error","Failed to refresh briefing"));
        }
    }

    // Personalized "For You" feed: match user saved interests directly against contextual categories (stored in tags)
    @GetMapping("/for-you")
    public ResponseEntity<Page<Article>> getPersonalized(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        try {
            User user = authHelperService.getUserFromAuthentication(authentication);
            String username = user.getUsername();
            logger.debug("Handling /for-you request for user: {} (page={}, size={})", username, page, size);
            List<String> interests = user.getInterests();
            List<String> professionCats = com.perplexity.newsaggregator.service.ProfessionCategoryMapper.map(user.getProfession());
            List<String> combined;
            boolean hasInterests = interests != null && interests.stream().anyMatch(s -> s != null && !s.isBlank());
            if (!hasInterests && !professionCats.isEmpty()) {
                combined = professionCats; // profession fallback only
                logger.debug("/for-you using profession fallback {} for user {}", combined, username);
            } else {
                combined = new java.util.ArrayList<>();
                if (hasInterests) combined.addAll(interests);
                combined.addAll(professionCats);
            }
            combined = combined.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
            Pageable pageable = getClampedPageable(page, size);
            if (combined.isEmpty()) {
                logger.info("User has neither interests nor effective profession mapping; returning recent articles.");
                return ResponseEntity.ok(newsService.getAllArticles(pageable));
            }
            Page<Article> result = newsService.getArticlesByTags(combined, pageable);
            if (!result.isEmpty()) {
                logger.info("Personalized feed returned {} articles for combined interests {} (profession: {})", result.getNumberOfElements(), combined, user.getProfession());
                return ResponseEntity.ok(result);
            }
            logger.info("No articles found matching combined set {}; returning recent articles", combined);
            return ResponseEntity.ok(newsService.getAllArticles(pageable));
        } catch (BadCredentialsException e) {
            logger.warn("Personalized feed authentication error: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().startsWith("User not found")) {
                return ResponseEntity.status(404).build();
            }
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Error fetching personalized feed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Global search endpoint - searches title, content, and summary
    @GetMapping("/search")
    public ResponseEntity<Page<Article>> searchArticles(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        try {
            if (q == null || q.trim().isEmpty()) {
                logger.warn("Empty search query provided");
                return ResponseEntity.badRequest().build();
            }
            
            Pageable pageable = getClampedPageable(page, size);
            Page<Article> articles = newsService.searchArticles(q.trim(), pageable);
            
            logger.info("Search for '{}' returned {} results out of {} total", 
                       q.trim(), articles.getNumberOfElements(), articles.getTotalElements());
            
            return ResponseEntity.ok(articles);
            
        } catch (Exception e) {
            logger.error("Error searching articles with query '{}': {}", q, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Get single article by ID
    @GetMapping("/{id}")
    public ResponseEntity<Article> getArticleById(@PathVariable String id) {
        try {
            Article article = newsService.getArticleById(id);
            
            if (article != null) {
                logger.debug("Found article with ID: {}", id);
                return ResponseEntity.ok(article);
            } else {
                logger.warn("Article not found with ID: {}", id);
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            logger.error("Error fetching article with ID '{}': {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Get all sources with their article counts for sidebar filtering
    @GetMapping("/sources-with-counts")
    public ResponseEntity<List<SourceCount>> getSourcesWithCounts() {
        try {
            List<SourceCount> sources = newsService.getSourcesWithArticleCounts();
            
            logger.debug("Returning {} sources with article counts", sources.size());
            return ResponseEntity.ok(sources);
            
        } catch (Exception e) {
            logger.error("Error fetching sources with counts: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // Get available news sources (sources that have at least one article)
    @GetMapping("/available-sources")
    public ResponseEntity<List<String>> getAvailableSources() {
        try {
            List<String> sources = newsService.getAvailableSourceNames();
            
            logger.debug("Returning {} available sources", sources.size());
            return ResponseEntity.ok(sources);
            
        } catch (Exception e) {
            logger.error("Error fetching available sources: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // On-demand limited Guardian API fetch
    @GetMapping("/guardian-api")
    public ResponseEntity<List<Article>> getGuardianApiArticles() {
        try {
            List<Article> articles = newsFetchingService.fetchGuardianApiArticlesLimited();
            return ResponseEntity.ok(articles);
        } catch (Exception e) {
            logger.error("Guardian API endpoint failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // On-demand GNews API fetch (returns articles without saving)
    @GetMapping("/gnews-api")
    public ResponseEntity<List<Article>> getGNewsArticles() {
        try {
            List<Article> articles = gNewsService.fetchGNewsArticles(20);
            return ResponseEntity.ok(articles);
        } catch (Exception e) {
            logger.error("GNews API endpoint failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // On-demand GNews API fetch and save to database
    @GetMapping("/gnews-api-save")
    public ResponseEntity<List<Article>> getAndSaveGNewsArticles() {
        try {
            logger.info("Fetching and saving GNews articles via API endpoint");
            List<Article> articles = gNewsService.fetchGNewsArticles(20);
            
            int savedCount = 0;
            List<Article> savedArticles = new ArrayList<>();
            
            for (Article article : articles) {
                try {
                    // Check if article already exists
                    if (!articleRepository.existsByUrl(article.getUrl())) {
                        Article savedArticle = articleRepository.save(article);
                        savedArticles.add(savedArticle);
                        savedCount++;
                        logger.debug("Saved GNews article: {}", article.getTitle());
                    } else {
                        logger.debug("Skipping duplicate GNews article: {}", article.getTitle());
                    }
                } catch (Exception e) {
                    logger.debug("Failed to save GNews article: {}", e.getMessage());
                }
            }
            
            logger.info("GNews API: Fetched {} articles, saved {} new articles", articles.size(), savedCount);
            return ResponseEntity.ok(savedArticles);
        } catch (Exception e) {
            logger.error("GNews API save endpoint failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Get articles from GNews (already saved in database)
    @GetMapping("/gnews-articles")
    public ResponseEntity<Page<Article>> getGNewsArticles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            Pageable pageable = getClampedPageable(page, size);
            
            // Get articles where sourceProvider = 'GNews'
            Page<Article> articles = articleRepository.findBySourceProviderOrderByPublishDateDesc("GNews", pageable);
            
            logger.debug("Returning {} GNews articles from database, page {}", 
                        articles.getNumberOfElements(), page);
            
            return ResponseEntity.ok(articles);
        } catch (Exception e) {
            logger.error("Failed to get GNews articles from database: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
    
    // Save GNews articles to database
    @PostMapping("/gnews-save")
    public ResponseEntity<String> saveGNewsArticles() {
        try {
            logger.info("Manual GNews save triggered via API");
            List<Article> articles = gNewsService.fetchGNewsArticles(80);
            
            int savedCount = 0;
            for (Article article : articles) {
                try {
                    // Check if article already exists
                    if (!articleRepository.existsByUrl(article.getUrl())) {
                        articleRepository.save(article);
                        savedCount++;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to save GNews article: {}", e.getMessage());
                }
            }
            
            String message = String.format("GNews save completed. Saved %d new articles out of %d fetched.", 
                                         savedCount, articles.size());
            logger.info(message);
            return ResponseEntity.ok(message);
        } catch (Exception e) {
            logger.error("Failed to save GNews articles: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to save GNews articles: " + e.getMessage());
        }
    }


    
    // Manual trigger for news fetching (useful for testing)
    @PostMapping("/fetch")
    public ResponseEntity<String> manualFetch() {
        try {
            logger.info("Manual news fetch triggered via API");
            newsFetchingService.fetchNewsManually();
            return ResponseEntity.ok("News fetching triggered successfully");
        } catch (Exception e) {
            logger.error("Failed to trigger manual news fetch: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to trigger news fetching: " + e.getMessage());
        }
    }
    
    // Clear all articles and trigger fresh fetch with optimized feeds (admin function)
    @PostMapping("/reset")
    public ResponseEntity<String> resetAndFetchNews() {
        try {
            logger.info("Reset and fresh fetch triggered via API - clearing all articles");
            
            // Clear all existing articles from database
            long deletedCount = newsService.deleteAllArticles();
            logger.info("Deleted {} existing articles from database", deletedCount);
            
            // Trigger fresh fetch with optimized feeds
            newsFetchingService.fetchNewsManually();
            
            return ResponseEntity.ok("Database cleared (" + deletedCount + " articles removed) and fresh news fetching triggered with optimized feeds");
        } catch (Exception e) {
            logger.error("Failed to reset and fetch news: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to reset and fetch news: " + e.getMessage());
        }
    }

    // Admin: Backfill missing tags for existing articles (process limited count per call)
    @PostMapping("/backfill-tags")
    public ResponseEntity<String> backfillTags(@RequestParam(defaultValue = "100") int limit) {
        try {
            int processed = newsService.backfillMissingTags(limit);
            return ResponseEntity.ok("Backfilled tags for " + processed + " articles (limit=" + limit + ")");
        } catch (Exception e) {
            logger.error("Failed to backfill tags: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to backfill tags: " + e.getMessage());
        }
    }

    // Admin: Migrate existing articles to improved AI tagging system
    @PostMapping("/migrate-tagging")
    public ResponseEntity<String> migrateTagging(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            logger.info("🚀 Starting AI tagging migration (limit: {}, force: {})", limit, force);
            int processed = newsService.migrateToImprovedTagging(limit, force);
            return ResponseEntity.ok(String.format(
                "✅ Migration complete! Processed %d articles with improved AI tagging (limit=%d, force=%s)", 
                processed, limit, force));
        } catch (Exception e) {
            logger.error("Failed to migrate tagging: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("❌ Migration failed: " + e.getMessage());
        }
    }

    // Performance test endpoint for sources
    @GetMapping("/sources-performance-test")
    public ResponseEntity<Map<String, Object>> testSourcesPerformance() {
        try {
            long startTime = System.currentTimeMillis();
            List<SourceCount> sources = newsService.getSourcesWithArticleCounts();
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> result = Map.of(
                "duration_ms", duration,
                "source_count", sources.size(),
                "method", "aggregation",
                "performance_rating", duration < 100 ? "excellent" : 
                                    duration < 500 ? "good" : 
                                    duration < 1000 ? "acceptable" : "slow"
            );
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Performance test failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Check migration status
    @GetMapping("/migration-status")
    public ResponseEntity<Map<String, Object>> getMigrationStatus() {
        try {
            long totalArticles = newsService.getTotalArticleCount();
            long articlesNeedingMigration = newsService.getArticlesNeedingMigrationCount();
            long migratedArticles = totalArticles - articlesNeedingMigration;
            
            double progressPercent = totalArticles > 0 ? (migratedArticles * 100.0 / totalArticles) : 0;
            
            Map<String, Object> status = Map.of(
                "totalArticles", totalArticles,
                "migratedArticles", migratedArticles,
                "articlesNeedingMigration", articlesNeedingMigration,
                "progressPercent", Math.round(progressPercent * 100.0) / 100.0,
                "isComplete", articlesNeedingMigration == 0
            );
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Failed to get migration status: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

}
