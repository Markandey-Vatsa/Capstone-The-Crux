package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.dto.GNewsApiResponse;
import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class GNewsService {
    
    private static final Logger logger = LoggerFactory.getLogger(GNewsService.class);
    
    @Value("${gnews.api.key:}")
    private String gNewsApiKey;
    
    // Rate limiting: Track last API call time
    private long lastApiCallTime = 0;
    private static final long MIN_API_INTERVAL_MS = 2000; // 2 seconds between calls
    
    @Autowired
    private ArticleRepository articleRepository;
    
    @Autowired
    private GroqService groqService;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    /**
     * Fetch articles from GNews API with Indian focus
     */
    public List<Article> fetchGNewsArticles(int maxArticles) {
        if (gNewsApiKey == null || gNewsApiKey.isBlank()) {
            logger.warn("GNews API key missing; cannot fetch articles");
            return List.of();
        }
        
        List<Article> allArticles = new ArrayList<>();
        
        // Fetch Indian news (reduced count for faster processing)
        allArticles.addAll(fetchFromEndpoint(
            "https://gnews.io/api/v4/top-headlines?token=" + gNewsApiKey + 
            "&lang=en&country=in&max=10", 
            "Indian Headlines"
        ));
        
        // Fetch India-related international news
        allArticles.addAll(fetchFromEndpoint(
            "https://gnews.io/api/v4/search?token=" + gNewsApiKey + 
            "&q=India&lang=en&max=10",
            "India Search"
        ));
        
        // Fetch technology news (international)
        allArticles.addAll(fetchFromEndpoint(
            "https://gnews.io/api/v4/search?token=" + gNewsApiKey + 
            "&q=technology&lang=en&max=5",
            "Technology"
        ));
        
        logger.info("GNews: Fetched {} total articles across all topics", allArticles.size());
        return allArticles.subList(0, Math.min(allArticles.size(), maxArticles));
    }
    
    private List<Article> fetchFromEndpoint(String url, String category) {
        try {
            // Rate limiting: Ensure minimum interval between API calls
            long currentTime = System.currentTimeMillis();
            long timeSinceLastCall = currentTime - lastApiCallTime;
            if (timeSinceLastCall < MIN_API_INTERVAL_MS) {
                long sleepTime = MIN_API_INTERVAL_MS - timeSinceLastCall;
                logger.debug("Rate limiting: Sleeping for {} ms", sleepTime);
                Thread.sleep(sleepTime);
            }
            lastApiCallTime = System.currentTimeMillis();
            
            logger.info("Fetching GNews {} articles from: {}", category, url.substring(0, 60) + "...");
            
            ResponseEntity<GNewsApiResponse> response = restTemplate.getForEntity(url, GNewsApiResponse.class);
            GNewsApiResponse body = response.getBody();
            
            if (body == null || body.getArticles() == null) {
                logger.warn("GNews {} returned empty response", category);
                return List.of();
            }
            
            List<Article> articles = new ArrayList<>();
            
            for (GNewsApiResponse.GNewsArticle item : body.getArticles()) {
                try {
                    // Skip if already exists
                    if (item.getUrl() == null || articleRepository.existsByUrl(item.getUrl())) {
                        continue;
                    }
                    
                    // Quality check - content length
                    String content = item.getContent() != null ? item.getContent() : item.getDescription();
                    if (content == null || content.length() < 200) {
                        logger.debug("Skipping GNews article due to short content: {} chars", 
                                   content != null ? content.length() : 0);
                        continue;
                    }
                    
                    // Language check
                    if (!"en".equals(item.getLang())) {
                        logger.debug("Skipping non-English article: {}", item.getLang());
                        continue;
                    }
                    
                    Article article = buildArticleFromGNews(item, category);
                    
                    // Skip AI enrichment for now to avoid timeouts
                    // TODO: Add AI enrichment in background processing
                    // enrichArticleWithAI(article, content);
                    
                    articles.add(article);
                    
                } catch (Exception e) {
                    logger.debug("Skipping GNews article due to error: {}", e.getMessage());
                }
            }
            
            logger.info("GNews {}: Processed {} valid articles", category, articles.size());
            return articles;
            
        } catch (Exception e) {
            logger.error("GNews {} fetch failed: {}", category, e.getMessage());
            return List.of();
        }
    }
    
    private Article buildArticleFromGNews(GNewsApiResponse.GNewsArticle item, String categoryHint) {
        Article article = new Article();
        
        // Basic fields
        article.setTitle(item.getTitle());
        article.setContent(item.getContent() != null ? item.getContent() : item.getDescription());
        article.setSummary(deriveSummary(item.getDescription(), 300));
        article.setUrl(item.getUrl());
        article.setImageUrl(item.getImage());
        
        // Parse date
        try {
            if (item.getPublishedAt() != null) {
                article.setPublishDate(Date.from(OffsetDateTime.parse(item.getPublishedAt()).toInstant()));
            } else {
                article.setPublishDate(new Date());
            }
        } catch (Exception e) {
            article.setPublishDate(new Date());
        }
        
        // Source information
        String sourceName = item.getSource() != null ? item.getSource().getName() : "Unknown";
        article.setSource(sourceName); // Keep for backward compatibility
        article.setOriginalSource(sourceName);
        article.setSourceProvider("GNews");
        
        // Quality metrics
        String content = article.getContent();
        int contentLength = content != null ? content.length() : 0;
        article.setContentLength(contentLength);
        article.setContentQuality(calculateContentQuality(contentLength));
        article.setQualityScore(calculateQualityScore(article));
        
        // Metadata
        article.setLanguage("en");
        article.setIsDuplicate(false);
        article.setLastUpdated(new Date());
        
        // Category mapping
        article.setCategory(mapCategoryFromSource(sourceName, categoryHint));
        
        return article;
    }
    
    private void enrichArticleWithAI(Article article, String content) {
        try {
            // Generate AI tags
            List<String> specificTags = groqService.generateTags(content);
            if (!specificTags.isEmpty()) {
                article.setTags(specificTags);
                
                // Map to domain categories
                List<String> domainCategories = groqService.mapTagsToDomains(specificTags);
                article.setDomainCategories(domainCategories);
            }
        } catch (Exception e) {
            logger.debug("AI enrichment failed for article: {}", e.getMessage());
            // Continue without AI tags
        }
    }
    
    private String calculateContentQuality(int contentLength) {
        if (contentLength >= 300) return "excellent";
        if (contentLength >= 200) return "acceptable";
        return "poor";
    }
    
    private double calculateQualityScore(Article article) {
        double score = 0.0;
        
        // Title quality (0-3 points)
        String title = article.getTitle();
        if (title != null && title.length() > 20) score += 2.0;
        if (title != null && title.length() > 50) score += 1.0;
        
        // Content quality (0-4 points)
        int contentLength = article.getContentLength() != null ? article.getContentLength() : 0;
        if (contentLength > 100) score += 1.0;
        if (contentLength > 200) score += 1.0;
        if (contentLength > 300) score += 2.0;
        
        // Metadata quality (0-3 points)
        if (article.getOriginalSource() != null) score += 1.0;
        if (article.getUrl() != null) score += 1.0;
        if (article.getImageUrl() != null) score += 1.0;
        
        return Math.min(score, 10.0);
    }
    
    private String mapCategoryFromSource(String sourceName, String categoryHint) {
        if (sourceName == null) return "General";
        
        String source = sourceName.toLowerCase();
        
        // Business sources
        if (source.contains("economic") || source.contains("business") || 
            source.contains("financial") || categoryHint.equals("Business")) {
            return "Business";
        }
        
        // Technology sources
        if (source.contains("tech") || categoryHint.equals("Technology")) {
            return "Technology";
        }
        
        // Health sources
        if (source.contains("health") || source.contains("medical")) {
            return "Health";
        }
        
        // Entertainment sources
        if (source.contains("entertainment") || source.contains("bollywood") || 
            source.contains("hollywood")) {
            return "Entertainment";
        }
        
        // Sports sources
        if (source.contains("sport") || source.contains("cricket")) {
            return "Sports";
        }
        
        return "General";
    }
    
    private String deriveSummary(String description, int maxChars) {
        if (description == null) return "";
        if (description.length() <= maxChars) return description;
        
        int lastPeriod = description.indexOf('.', Math.min(description.length() - 1, maxChars - 40));
        if (lastPeriod > 0 && lastPeriod < maxChars + 60) {
            return description.substring(0, lastPeriod + 1);
        }
        return description.substring(0, maxChars - 3) + "...";
    }
}