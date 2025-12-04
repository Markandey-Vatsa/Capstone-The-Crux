package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Service responsible for article search and retrieval operations.
 * Handles search by keywords, tags, sources, and basic CRUD operations.
 */
@Service
public class ArticleSearchService {
    
    private static final Logger logger = LoggerFactory.getLogger(ArticleSearchService.class);
    
    @Autowired
    private ArticleRepository articleRepository;
    
    /**
     * Get all articles with pagination, ordered by publish date descending
     */
    public Page<Article> getAllArticles(Pageable pageable) {
        logger.debug("Fetching all articles with pagination: page={}, size={}", 
                    pageable.getPageNumber(), pageable.getPageSize());
        return articleRepository.findAllByOrderByPublishDateDesc(pageable);
    }
    
    /**
     * Get articles by a single contextual tag (used for dynamic interest categories on UI)
     */
    public Page<Article> getArticlesByTag(String tag, Pageable pageable) {
        if (tag == null || tag.isBlank()) return Page.empty(pageable);
        logger.debug("Fetching articles by tag '{}' page={}, size={}", tag, pageable.getPageNumber(), pageable.getPageSize());
        return articleRepository.findByTagsContainingIgnoreCaseOrderByPublishDateDesc(tag, pageable);
    }
    
    /**
     * Search articles by keyword in title, content, or summary
     * This is the main global search method
     */
    public Page<Article> searchArticles(String keyword, Pageable pageable) {
        if (keyword == null || keyword.trim().isEmpty()) {
            logger.debug("Empty search keyword provided, returning all articles");
            return getAllArticles(pageable);
        }
        
        String trimmedKeyword = keyword.trim();
        logger.info("Searching articles with keyword '{}', page={}, size={}", 
                   trimmedKeyword, pageable.getPageNumber(), pageable.getPageSize());
        
        // ENHANCED SEARCH: Prioritizes tags, then content (so "star formation" finds nebula articles)
        String regex = ".*" + Pattern.quote(trimmedKeyword) + ".*";
        Page<Article> results;
        try {
            // First try: Enhanced search with tag priority (searches specific tags first)
            results = articleRepository.searchByKeywordWithTagPriority(regex, pageable);
            
            if (results.isEmpty()) {
                // Second try: Original comprehensive search
                results = articleRepository.searchByKeywordRegex(regex, pageable);
            }
        } catch (Exception ex) {
            logger.warn("Enhanced search failed for keyword '{}' due to {}. Falling back to legacy text search.",
                    trimmedKeyword, ex.getMessage());
            results = Page.empty(pageable);
        }

        if (results.isEmpty()) {
            logger.debug("All regex searches returned no results, falling back to legacy text search for '{}'.", trimmedKeyword);
            results = articleRepository
                .findByTitleContainingIgnoreCaseOrContentContainingIgnoreCaseOrSummaryContainingIgnoreCase(
                    trimmedKeyword, trimmedKeyword, trimmedKeyword, pageable);
        }
        
        // Enhanced search analytics
        long totalResults = results.getTotalElements();
        logger.info("🔍 SEARCH ANALYTICS: '{}' → {} articles found", trimmedKeyword, totalResults);
        
        if (totalResults > 0 && logger.isDebugEnabled()) {
            // Log sample results for debugging
            List<Article> sampleResults = results.getContent().stream().limit(3).toList();
            for (Article article : sampleResults) {
                logger.debug("  📄 Found: '{}' | Tags: {} | Specific: {}", 
                    article.getTitle(), 
                    article.getTags(), 
                    article.getDomainCategories());
            }
        }
        
        return results;
    }
    
    /**
     * Get a single article by ID
     */
    public Article getArticleById(String id) {
        logger.debug("Fetching article with ID: {}", id);
        return articleRepository.findById(id).orElse(null);
    }
    
    /**
     * Check if an article exists by URL
     */
    public boolean articleExistsByUrl(String url) {
        return articleRepository.existsByUrl(url);
    }
    
    /**
     * Get total count of articles
     */
    public long getTotalArticleCount() {
        return articleRepository.count();
    }
    
    /**
     * Get all articles by source with pagination
     */
    public Page<Article> getArticlesBySource(String source, Pageable pageable) {
        logger.debug("Fetching articles by source '{}' with pagination: page={}, size={}", 
                    source, pageable.getPageNumber(), pageable.getPageSize());
        return articleRepository.findBySourceOrderByPublishDateDesc(source, pageable);
    }
    
    /**
     * Get articles by any matching tag (personalized feed via AI tags)
     */
    public Page<Article> getArticlesByTags(List<String> tags, Pageable pageable) {
        if (tags == null || tags.isEmpty()) return Page.empty(pageable);
        // Preserve capitalization because stored categories use capitalization
        List<String> query = tags.stream().filter(t -> t != null && !t.trim().isEmpty()).distinct().collect(java.util.stream.Collectors.toList());
        if (query.isEmpty()) return Page.empty(pageable);
        logger.debug("Fetching personalized articles for contextual categories {} page={}, size={}", query, pageable.getPageNumber(), pageable.getPageSize());
        return articleRepository.findByTagsInOrderByPublishDateDesc(query, pageable);
    }
    /**
     * Delete all articles from the database (admin function)
     * Returns the number of articles deleted
     */
    public long deleteAllArticles() {
        logger.warn("ADMIN ACTION: Deleting all articles from database");
        long count = articleRepository.count();
        articleRepository.deleteAll();
        logger.warn("Deleted {} articles from database", count);
        return count;
    }
}