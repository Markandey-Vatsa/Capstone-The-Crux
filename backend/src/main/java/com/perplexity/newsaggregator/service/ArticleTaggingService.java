package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.util.LoggingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service responsible for article tagging operations including backfill and migration.
 * Handles AI-powered tagging, migration to improved tagging systems, and tag quality assessment.
 */
@Service
public class ArticleTaggingService {
    
    private static final Logger logger = LoggerFactory.getLogger(ArticleTaggingService.class);
    
    @Autowired
    private ArticleRepository articleRepository;
    
    @Autowired
    private GroqService groqService;
    
    // Configurable delay between Groq calls during backfill (ms)
    @Value("${groq.backfill.delay-ms:2500}")
    private long backfillDelayMs;

    // Track last Groq call timestamp to enforce minimum spacing (extra safety)
    private volatile long lastGroqCallTs = 0L;
    
    @Value("${app.auto-migration.enabled:true}")
    private boolean autoMigrationEnabled;
    
    @Value("${app.auto-migration.batch-size:50}")
    private int autoMigrationBatchSize;
    
    @Value("${app.auto-migration.delay-minutes:5}")
    private int autoMigrationDelayMinutes;
    
    // Word count threshold for keyword tagging
    @Value("${app.tagging.word-count-threshold:50}")
    private int wordCountThreshold;
    
    // Confidence threshold for keyword filtering
    @Value("${app.tagging.confidence-threshold:0.6}")
    private double confidenceThreshold;
    
    /**
     * Backfill tags for existing articles that are missing them. Processes in batches to avoid memory issues.
     * Returns number of articles updated.
     */
    public int backfillMissingTags(int maxToProcess) {
        // Backfill start (could set a shared flag if ingestion needs to observe it)
        List<Article> all = articleRepository.findAll();
        int updated = 0;
        for (Article a : all) {
            if (updated >= maxToProcess) break;
            boolean needsTags = (a.getTags() == null || a.getTags().isEmpty());
            boolean needsDomain = (a.getDomainCategories() == null || a.getDomainCategories().isEmpty());
            if (needsTags || needsDomain) {
                // Enforce min delay based on last timestamp (token bucket style simple gate)
                long now = System.currentTimeMillis();
                long waitNeeded = backfillDelayMs - (now - lastGroqCallTs);
                if (waitNeeded > 0) {
                    try {
                        Thread.sleep(waitNeeded);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Backfill wait interrupted: {}", ie.getMessage());
                    }
                }
                // IMPROVED AI TAGGING SYSTEM: Two-pass approach for backfill
                String content = a.getContent() != null ? a.getContent() : a.getSummary();
                
                // Check word count threshold before calling API
                if (!meetsWordCountThreshold(content)) {
                    logger.debug("Skipping tagging for article '{}' - below word count threshold", 
                        truncateTitle(a.getTitle()));
                    continue;
                }
                
                // FIRST PASS: Generate specific tags
                List<String> specificTags = groqService.generateTags(content);
                lastGroqCallTs = System.currentTimeMillis();
                
                if (!specificTags.isEmpty()) {
                    if (needsDomain) {
                        a.setDomainCategories(List.copyOf(specificTags));
                    }
                    
                    // SECOND PASS: Map to main domains with learning
                    List<String> mainDomains = groqService.mapTagsToDomains(specificTags);
                    
                    if (mainDomains.isEmpty()) {
                        // Fallback mapping
                        TaxonomyUtil.guessBucketFromText(content).ifPresent(domain -> mainDomains.add(domain));
                    }
                    
                    if (needsTags && !mainDomains.isEmpty()) {
                        // Use enhanced domain assignment to preserve existing domains
                        List<String> enhancedDomains = enhancedDomainAssignment(a.getTags(), mainDomains);
                        a.setTags(enhancedDomains);
                    }
                    
                    if ((needsTags && !mainDomains.isEmpty()) || needsDomain) {
                        articleRepository.save(a);
                        updated++;
                        
                        // Enhanced logging for confidence monitoring
                        logger.info("✅ BACKFILL SUCCESS: Article '{}' | Keywords: {} | Domains: {} | WordCount: {}", 
                            truncateTitle(a.getTitle()), specificTags.size(), mainDomains.size(), 
                            content != null ? content.split("\\s+").length : 0);
                        logger.debug("Backfill details: keywords={} -> domains={}", specificTags, mainDomains);
                    }
                }
            }
        }
        logger.info("Backfill operation added tags to {} articles (requested max {}).", updated, maxToProcess);
        // Backfill end
        return updated;
    }
    
    /**
     * AUTOMATIC BACKGROUND MIGRATION: Runs automatically on startup
     * Processes articles in background without manual intervention
     */
    @Async
    @EventListener(ContextRefreshedEvent.class)
    public void autoMigrateOnStartup() {
        if (!autoMigrationEnabled) {
            LoggingUtil.logSystem(logger, "AUTO-MIGRATION: Disabled via configuration");
            return;
        }
        
        // Wait 30 seconds after startup to let everything initialize
        try {
            Thread.sleep(30000);
            LoggingUtil.logSystem(logger, "AUTO-MIGRATION: Starting automatic background migration");
            LoggingUtil.logSystem(logger, "AUTO-MIGRATION: Settings configured", 
                "batchSize", autoMigrationBatchSize, "delayMinutes", autoMigrationDelayMinutes);
            
            // Run migration in background thread
            new Thread(() -> {
                try {
                    runCompleteMigration();
                } catch (Exception e) {
                    logger.error("Auto-migration failed: {}", e.getMessage());
                }
            }).start();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Run complete migration automatically in background
     */
    private void runCompleteMigration() {
        LoggingUtil.PerformanceTimer migrationTimer = LoggingUtil.startTimer();
        LoggingUtil.logSystem(logger, "STARTING COMPLETE AUTO-MIGRATION");
        
        int totalProcessed = 0;
        int maxIterations = 50; // Prevent infinite loops
        int iteration = 0;
        long delayMs = autoMigrationDelayMinutes * 60 * 1000L; // Convert minutes to milliseconds
        
        while (iteration < maxIterations) {
            iteration++;
            
            try {
                // Process batch
                int processed = migrateToImprovedTagging(autoMigrationBatchSize, false);
                totalProcessed += processed;
                
                LoggingUtil.logMigration(logger, "Batch " + iteration, processed, totalProcessed);
                
                // If no articles were processed, migration is complete
                if (processed == 0) {
                    LoggingUtil.logSuccess(logger, "AUTO-MIGRATION COMPLETE", 
                        "totalMigrated", totalProcessed, "batches", iteration);
                    break;
                }
                
                // Wait configured time between batches
                LoggingUtil.logSystem(logger, "AUTO-MIGRATION: Waiting before next batch", 
                    "delayMinutes", autoMigrationDelayMinutes);
                Thread.sleep(delayMs);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Auto-migration interrupted");
                break;
            } catch (Exception e) {
                logger.error("Auto-migration batch {} failed: {}", iteration, e.getMessage());
                // Wait longer on error, then continue
                try {
                    long errorDelayMs = delayMs * 2; // Double the delay on error
                    logger.info("⚠️ AUTO-MIGRATION: Error occurred, waiting {} minutes before retry...", errorDelayMs / 60000);
                    Thread.sleep(errorDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        migrationTimer.logCompletion(logger, "AUTO-MIGRATION FINISHED", 
            "totalProcessed", totalProcessed, "batches", iteration);
    }

    /**
     * ENHANCED MIGRATION: Re-process existing articles with improved AI tagging system
     * Respects rate limits and processes articles in small batches
     * Can be run multiple times safely - only processes articles that need updating
     */
    public int migrateToImprovedTagging(int maxToProcess, boolean forceReprocess) {
        logger.info("🔄 Starting migration to improved AI tagging system (max: {}, force: {})", maxToProcess, forceReprocess);
        
        List<Article> candidates;
        if (forceReprocess) {
            // Force reprocess all articles (for testing new prompts)
            candidates = articleRepository.findAll().stream()
                .sorted((a, b) -> b.getPublishDate().compareTo(a.getPublishDate())) // Newest first
                .limit(maxToProcess)
                .toList();
        } else {
            // Only process articles with old/poor tagging
            candidates = articleRepository.findAll().stream()
                .filter(this::needsTaggingImprovement)
                .sorted((a, b) -> b.getPublishDate().compareTo(a.getPublishDate())) // Newest first
                .limit(maxToProcess)
                .toList();
        }
        
        logger.info("📊 Found {} articles that need improved tagging", candidates.size());
        
        int updated = 0;
        int apiCalls = 0;
        long startTime = System.currentTimeMillis();
        
        for (Article article : candidates) {
            if (updated >= maxToProcess) break;
            
            try {
                // Rate limiting: Ensure minimum delay between API calls
                long now = System.currentTimeMillis();
                long waitNeeded = backfillDelayMs - (now - lastGroqCallTs);
                if (waitNeeded > 0) {
                    logger.debug("⏳ Rate limiting: waiting {}ms", waitNeeded);
                    Thread.sleep(waitNeeded);
                }
                
                String content = article.getContent() != null ? article.getContent() : article.getSummary();
                if (content == null || content.trim().isEmpty()) {
                    logger.debug("⚠️ Skipping article with no content: {}", article.getTitle());
                    continue;
                }
                
                // Check word count threshold before calling API
                if (!meetsWordCountThreshold(content)) {
                    logger.debug("⚠️ Skipping article '{}' - below word count threshold", 
                        truncateTitle(article.getTitle()));
                    continue;
                }
                
                // Store original tags for comparison
                List<String> originalTags = article.getTags() != null ? new ArrayList<>(article.getTags()) : List.of();
                List<String> originalDomainCategories = article.getDomainCategories() != null ? new ArrayList<>(article.getDomainCategories()) : List.of();
                
                // FIRST PASS: Generate improved specific tags
                List<String> newSpecificTags = groqService.generateTags(content);
                lastGroqCallTs = System.currentTimeMillis();
                apiCalls++;
                
                if (!newSpecificTags.isEmpty()) {
                    // SECOND PASS: Map to domains with learning
                    List<String> newMainDomains = groqService.mapTagsToDomains(newSpecificTags);
                    apiCalls++;
                    
                    if (newMainDomains.isEmpty()) {
                        // Fallback mapping
                        TaxonomyUtil.guessBucketFromText(content).ifPresent(newMainDomains::add);
                    }
                    
                    // Use enhanced domain assignment to preserve existing valid domains
                    List<String> enhancedDomains = enhancedDomainAssignment(originalTags, newMainDomains);
                    
                    // CHECK: Only save and count as migrated if tags actually changed
                    boolean tagsChanged = !originalTags.equals(enhancedDomains) || 
                                        !originalDomainCategories.equals(newSpecificTags);
                    
                    if (tagsChanged) {
                        // Update article with improved tags
                        article.setDomainCategories(List.copyOf(newSpecificTags));
                        article.setTags(enhancedDomains);
                        
                        articleRepository.save(article);
                        updated++;
                        
                        // Enhanced logging for migration tracking
                        logger.info("✅ MIGRATED [{}]: '{}' | Old: {} → Enhanced: {} | Specific: {}", 
                            updated, 
                            truncateTitle(article.getTitle()), 
                            originalTags, 
                            enhancedDomains,
                            newSpecificTags.size());
                    } else {
                        logger.debug("⏭️ SKIPPED [{}]: '{}' | Tags unchanged: {}", 
                            updated + 1,
                            truncateTitle(article.getTitle()), 
                            originalTags);
                    }
                        
                } else {
                    logger.warn("⚠️ No tags generated for: {}", truncateTitle(article.getTitle()));
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Migration interrupted: {}", e.getMessage());
                break;
            } catch (Exception e) {
                logger.error("Failed to migrate article '{}': {}", truncateTitle(article.getTitle()), e.getMessage());
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("🎉 MIGRATION COMPLETE: {} articles updated, {} API calls, {}ms duration", 
            updated, apiCalls, duration);
        
        // Log confidence metrics after migration for monitoring
        if (updated > 0) {
            logConfidenceMetrics();
        }
            
        return updated;
    }
    
    /**
     * Check if an article needs tagging improvement (updated for conservative tagging)
     */
    private boolean needsTaggingImprovement(Article article) {
        // Check article length first - very short articles might not need tags
        String content = article.getContent() != null ? article.getContent() : article.getSummary();
        if (content != null) {
            int wordCount = content.split("\\s+").length;
            if (wordCount < 20) {
                // Very short articles don't need tags - mark as processed
                return false;
            }
        }
        
        // Articles with no tags definitely need improvement
        if (article.getTags() == null || article.getTags().isEmpty()) {
            return true;
        }
        
        // Articles with no specific tags need improvement
        if (article.getDomainCategories() == null || article.getDomainCategories().isEmpty()) {
            return true;
        }
        
        // Check for excessive tagging (old system was too aggressive)
        List<String> specificTags = article.getDomainCategories();
        if (specificTags.size() > 8) {
            // Too many tags - needs conservative re-tagging
            return true;
        }
        
        // Articles with generic/poor quality tags
        long genericCount = specificTags.stream()
            .filter(tag -> isGenericTag(tag))
            .count();
            
        // If more than 30% of tags are generic, needs improvement (lowered threshold)
        if (genericCount > specificTags.size() * 0.3) {
            return true;
        }
        
        // Check for high-quality, conservative tags
        boolean hasHighQualityTags = specificTags.stream()
            .allMatch(tag -> isHighQualityTag(tag));
            
        // If already has high-quality tags and reasonable count, probably doesn't need reprocessing
        return !hasHighQualityTags;
    }
    
    /**
     * Check if a tag is generic/low quality (updated for conservative tagging)
     */
    private boolean isGenericTag(String tag) {
        if (tag == null) return true;
        String lower = tag.toLowerCase();
        
        // Expanded list of generic terms to avoid
        Set<String> genericTerms = Set.of(
            "news", "update", "report", "article", "story", "latest", "new", "recent",
            "technology", "innovation", "development", "growth", "business", "company",
            "industry", "market", "sector", "analysis", "research", "study", "data",
            "information", "system", "solution", "service", "product", "platform",
            "advanced", "cutting-edge", "revolutionary", "breakthrough", "amazing",
            "incredible", "fantastic", "great", "excellent", "outstanding"
        );
        
        return lower.length() < 3 || 
               lower.matches("^\\d+$") || // Just numbers
               genericTerms.contains(lower) ||
               lower.matches(".*ing$") || // Avoid gerunds like "processing", "developing"
               lower.matches(".*tion$"); // Avoid abstract nouns like "innovation", "creation"
    }
    
    /**
     * Check if a tag is high quality (specific, factual, conservative)
     */
    private boolean isHighQualityTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) return false;
        
        String trimmed = tag.trim();
        
        // High-quality tags for conservative system:
        // - At least 3 characters long (reduced from 5)
        // - Specific company, product, or technology names
        // - Proper nouns or concrete entities
        // - Not generic or abstract terms
        
        boolean isProperNoun = Character.isUpperCase(trimmed.charAt(0));
        boolean isSpecificEntity = trimmed.matches(".*[A-Z].*"); // Contains uppercase (likely proper noun)
        boolean isConcreteEntity = !trimmed.matches(".*ing$") && !trimmed.matches(".*tion$"); // Not abstract
        
        return trimmed.length() >= 3 && 
               !isGenericTag(trimmed) &&
               (isProperNoun || isSpecificEntity) &&
               isConcreteEntity;
    }
    
    /**
     * Truncate title for logging
     */
    private String truncateTitle(String title) {
        if (title == null) return "Unknown";
        return title.length() > 50 ? title.substring(0, 47) + "..." : title;
    }
    
    /**
     * Get count of articles that need migration
     */
    public long getArticlesNeedingMigrationCount() {
        return articleRepository.findAll().stream()
            .filter(this::needsTaggingImprovement)
            .count();
    }
    
    /**
     * Check if article content meets minimum word count threshold for tagging
     */
    private boolean meetsWordCountThreshold(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        int wordCount = content.trim().split("\\s+").length;
        boolean meetsThreshold = wordCount >= wordCountThreshold;
        
        if (!meetsThreshold) {
            logger.debug("Article content below word count threshold: {} words (minimum: {})", 
                wordCount, wordCountThreshold);
        }
        
        return meetsThreshold;
    }
    
    /**
     * Enhanced domain assignment that preserves existing valid domains
     * and merges them with new domains from keyword mapping
     */
    private List<String> enhancedDomainAssignment(List<String> existingDomains, List<String> newDomains) {
        Set<String> mergedDomains = new LinkedHashSet<>();
        
        // Add existing valid domains
        if (existingDomains != null) {
            existingDomains.stream()
                .filter(domain -> domain != null && !domain.trim().isEmpty())
                .forEach(mergedDomains::add);
        }
        
        // Add new domains
        if (newDomains != null) {
            newDomains.stream()
                .filter(domain -> domain != null && !domain.trim().isEmpty())
                .forEach(mergedDomains::add);
        }
        
        List<String> result = new ArrayList<>(mergedDomains);
        
        // Log domain assignment details
        if (!result.equals(existingDomains)) {
            logger.debug("Domain assignment: {} existing + {} new = {} total domains", 
                existingDomains != null ? existingDomains.size() : 0,
                newDomains != null ? newDomains.size() : 0,
                result.size());
        }
        
        return result;
    }
    
    /**
     * Log confidence and quality metrics for monitoring keyword accuracy
     */
    public void logConfidenceMetrics() {
        try {
            List<Article> recentArticles = articleRepository.findAll().stream()
                .filter(a -> a.getTags() != null && !a.getTags().isEmpty())
                .filter(a -> a.getDomainCategories() != null && !a.getDomainCategories().isEmpty())
                .sorted((a, b) -> b.getPublishDate().compareTo(a.getPublishDate()))
                .limit(100) // Last 100 tagged articles
                .toList();
            
            if (recentArticles.isEmpty()) {
                logger.info("📊 CONFIDENCE METRICS: No tagged articles found for analysis");
                return;
            }
            
            int totalArticles = recentArticles.size();
            int articlesWithKeywords = (int) recentArticles.stream()
                .filter(a -> a.getDomainCategories() != null && !a.getDomainCategories().isEmpty())
                .count();
            
            int articlesWithDomains = (int) recentArticles.stream()
                .filter(a -> a.getTags() != null && !a.getTags().isEmpty())
                .count();
            
            double avgKeywordsPerArticle = recentArticles.stream()
                .filter(a -> a.getDomainCategories() != null)
                .mapToInt(a -> a.getDomainCategories().size())
                .average()
                .orElse(0.0);
            
            double avgDomainsPerArticle = recentArticles.stream()
                .filter(a -> a.getTags() != null)
                .mapToInt(a -> a.getTags().size())
                .average()
                .orElse(0.0);
            
            logger.info("📊 CONFIDENCE METRICS SUMMARY:");
            logger.info("   📈 Total Articles Analyzed: {}", totalArticles);
            logger.info("   🏷️  Articles with Keywords: {} ({:.1f}%)", 
                articlesWithKeywords, (articlesWithKeywords * 100.0 / totalArticles));
            logger.info("   🎯 Articles with Domains: {} ({:.1f}%)", 
                articlesWithDomains, (articlesWithDomains * 100.0 / totalArticles));
            logger.info("   📊 Avg Keywords per Article: {:.2f}", avgKeywordsPerArticle);
            logger.info("   📊 Avg Domains per Article: {:.2f}", avgDomainsPerArticle);
            logger.info("   ⚙️  Word Count Threshold: {} words", wordCountThreshold);
            logger.info("   ⚙️  Confidence Threshold: {:.2f}", confidenceThreshold);
            
        } catch (Exception e) {
            logger.error("Failed to generate confidence metrics: {}", e.getMessage());
        }
    }
}