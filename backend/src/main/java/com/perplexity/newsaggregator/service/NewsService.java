package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Main NewsService that delegates to specialized services for different concerns.
 * This service acts as a facade and maintains backward compatibility.
 */
@Service
public class NewsService {
    
    private static final Logger logger = LoggerFactory.getLogger(NewsService.class);
    
    @Autowired
    private ArticleSearchService articleSearchService;
    
    @Autowired
    private ArticleSourceService articleSourceService;
    
    @Autowired
    private ArticleTaggingService articleTaggingService;
    
    @Autowired
    private DailyBriefingService dailyBriefingService;
    
    // ================= Article Search Operations (Delegated) =================
    
    public Page<Article> getAllArticles(Pageable pageable) {
        return articleSearchService.getAllArticles(pageable);
    }
    
    public Page<Article> searchArticles(String keyword, Pageable pageable) {
        return articleSearchService.searchArticles(keyword, pageable);
    }
    
    public Article getArticleById(String id) {
        return articleSearchService.getArticleById(id);
    }
    
    public boolean articleExistsByUrl(String url) {
        return articleSearchService.articleExistsByUrl(url);
    }
    
    public long getTotalArticleCount() {
        return articleSearchService.getTotalArticleCount();
    }
    
    public long deleteAllArticles() {
        return articleSearchService.deleteAllArticles();
    }
    
    public Page<Article> getArticlesBySource(String source, Pageable pageable) {
        return articleSearchService.getArticlesBySource(source, pageable);
    }
    
    public Page<Article> getArticlesByTags(List<String> tags, Pageable pageable) {
        return articleSearchService.getArticlesByTags(tags, pageable);
    }

    public Page<Article> getArticlesByTag(String tag, Pageable pageable) {
        return articleSearchService.getArticlesByTag(tag, pageable);
    }

    // ================= Daily Briefing Operations (Delegated) =================
    
    public String buildDailyBriefing(List<String> interests) {
        return dailyBriefingService.buildDailyBriefing(interests);
    }
    
    public String buildDailyBriefing(List<String> interests, String username, String profession, String language, String daypart) {
        return dailyBriefingService.buildDailyBriefing(interests, username, profession, language, daypart);
    }
    
    public void invalidateBriefingCache(String username) {
        dailyBriefingService.invalidateBriefingCache(username);
    }

    public String regenerateBriefingNow(User user) {
        return dailyBriefingService.regenerateBriefingNow(user);
    }
    
    public List<String> buildStructuredBriefing(User user) {
        return dailyBriefingService.buildStructuredBriefing(user);
    }

    // ================= Article Tagging Operations (Delegated) =================
    
    public int backfillMissingTags(int maxToProcess) {
        return articleTaggingService.backfillMissingTags(maxToProcess);
    }
    
    public int migrateToImprovedTagging(int maxToProcess, boolean forceReprocess) {
        return articleTaggingService.migrateToImprovedTagging(maxToProcess, forceReprocess);
    }
    
    public long getArticlesNeedingMigrationCount() {
        return articleTaggingService.getArticlesNeedingMigrationCount();
    }

    // ================= Article Source Operations (Delegated) =================
    
    public List<ArticleSourceService.SourceCount> getSourcesWithArticleCounts() {
        return articleSourceService.getSourcesWithArticleCounts();
    }
    
    public List<String> getAvailableSourceNames() {
        return articleSourceService.getAvailableSourceNames();
    }
    
    public List<ArticleSourceService.SourceCount> getSourcesWithCountsFiltered(List<String> pinnedSources) {
        return articleSourceService.getSourcesWithCountsFiltered(pinnedSources);
    }
    
    // Legacy DTO class for backward compatibility
    public static class SourceCount extends ArticleSourceService.SourceCount {
        public SourceCount(String sourceName, int articleCount) {
            super(sourceName, articleCount);
        }
    }
}