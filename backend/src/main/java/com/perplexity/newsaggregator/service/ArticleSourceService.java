package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service responsible for managing article sources and source-related operations.
 * Handles source counting, filtering, and source-based queries.
 */
@Service
public class ArticleSourceService {
    
    private static final Logger logger = LoggerFactory.getLogger(ArticleSourceService.class);
    
    @Autowired
    private ArticleRepository articleRepository;
    
    /**
     * Get sources with their article counts for sidebar filtering
     * Returns a list of SourceCount objects containing source name and count
     */
    public List<SourceCount> getSourcesWithArticleCounts() {
        logger.debug("Fetching all sources with article counts using efficient aggregation");
        
        try {
            // Use efficient MongoDB aggregation instead of loading all articles
            List<SourceCount> result = articleRepository.getSourceCounts();
            
            logger.info("Found {} sources with article counts (aggregation query)", result.size());
            return result;
            
        } catch (Exception e) {
            logger.warn("Aggregation query failed, falling back to in-memory grouping: {}", e.getMessage());
            
            // Fallback to original method if aggregation fails
            List<Article> allArticles = articleRepository.findAll();
            
            Map<String, Long> sourceCounts = allArticles.stream()
                .collect(Collectors.groupingBy(Article::getSource, Collectors.counting()));
            
            List<SourceCount> result = sourceCounts.entrySet().stream()
                .map(entry -> new SourceCount(entry.getKey(), entry.getValue().intValue()))
                .sorted((a, b) -> Integer.compare(b.getArticleCount(), a.getArticleCount()))
                .collect(Collectors.toList());
            
            logger.info("Found {} sources with article counts (fallback method)", result.size());
            return result;
        }
    }
    
    /**
     * Get list of available source names (sources that have at least one article)
     * Used for source selection in user preferences
     */
    public List<String> getAvailableSourceNames() {
        logger.debug("Fetching available source names");
        
        try {
            // Get all articles with only source field, then extract distinct sources
            List<Article> articlesWithSources = articleRepository.findDistinctSourcesRaw();
            
            List<String> sources = articlesWithSources.stream()
                .map(Article::getSource)
                .filter(source -> source != null && !source.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
            
            logger.info("Found {} available sources", sources.size());
            return sources;
                
        } catch (Exception e) {
            logger.error("Error fetching available source names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Get source counts filtered by user's pinned sources
     * Returns only the sources that the user has pinned with their article counts
     */
    public List<SourceCount> getSourcesWithCountsFiltered(List<String> pinnedSources) {
        if (pinnedSources == null || pinnedSources.isEmpty()) {
            logger.debug("No pinned sources provided, returning all sources");
            return getSourcesWithArticleCounts();
        }
        
        logger.debug("Fetching source counts for {} pinned sources", pinnedSources.size());
        
        try {
            // Get all source counts first
            List<SourceCount> allSources = getSourcesWithArticleCounts();
            
            // Filter to only include pinned sources
            List<SourceCount> filteredSources = allSources.stream()
                .filter(sourceCount -> pinnedSources.contains(sourceCount.getSourceName()))
                .collect(Collectors.toList());
            
            logger.info("Filtered to {} sources from {} pinned sources", filteredSources.size(), pinnedSources.size());
            return filteredSources;
            
        } catch (Exception e) {
            logger.error("Error fetching filtered source counts: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * DTO class for source count data
     */
    public static class SourceCount {
        private String sourceName;
        private int articleCount;
        
        public SourceCount(String sourceName, int articleCount) {
            this.sourceName = sourceName;
            this.articleCount = articleCount;
        }
        
        public String getSourceName() {
            return sourceName;
        }
        
        public void setSourceName(String sourceName) {
            this.sourceName = sourceName;
        }
        
        public int getArticleCount() {
            return articleCount;
        }
        
        public void setArticleCount(int articleCount) {
            this.articleCount = articleCount;
        }
    }
}