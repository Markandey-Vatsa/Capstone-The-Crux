package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import com.perplexity.newsaggregator.dto.GuardianApiResponse;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

@Service
public class NewsFetchingService {
    
    private static final Logger logger = LoggerFactory.getLogger(NewsFetchingService.class);
    
    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private GroqService groqService; // Contextual categorization service

    @Autowired
    private GNewsService gNewsService; // GNews API integration

    @Value("${guardian.api.key:}")
    private String guardianApiKey;



    private final RestTemplate restTemplate = new RestTemplate();

    // Delay between Groq API calls during ingestion to respect rate limits (ms)
    @Value("${groq.ingest.delay-ms:2100}")
    private long groqIngestDelayMs;
    
    // Primary RSS feed map (can be disabled or overridden through properties)
    private final Map<String, Map<String, String>> RSS_FEEDS = new HashMap<>();

    @Value("${app.rss.feeds.enabled:true}")
    private boolean rssFeedsEnabled;

    // Allow specifying a reduced feed list (comma-separated URLs) for certain profiles (e.g. test)
    @Value("${app.rss.feeds.override:}")
    private String feedsOverride;

    @Value("${app.rss.feeds.default:true}")
    private boolean loadDefaultFeeds; // set false to start with empty list

    public NewsFetchingService() {
        // defaults loaded later in @PostConstruct to allow property injection
    }

    @jakarta.annotation.PostConstruct
    void initFeeds() {
        if (loadDefaultFeeds) {
            // Technology
            RSS_FEEDS.put("https://techcrunch.com/feed/", Map.of("category", "Technology", "source", "TechCrunch"));
            // Business
            RSS_FEEDS.put("https://economictimes.indiatimes.com/rssfeedstopstories.cms", Map.of("category", "Business", "source", "Economic Times"));
            // Health
            RSS_FEEDS.put("https://www.sciencedaily.com/rss/health_medicine.xml", Map.of("category", "Health", "source", "ScienceDaily Health"));
            // Space
            RSS_FEEDS.put("https://www.nasa.gov/rss/dyn/breaking_news.rss", Map.of("category", "Space", "source", "NASA Breaking"));
            RSS_FEEDS.put("https://feeds.feedburner.com/spaceflightnow", Map.of("category", "Space", "source", "Spaceflight Now"));
            // Entertainment
            RSS_FEEDS.put("https://www.bollywoodhungama.com/rss/news.xml", Map.of("category", "Entertainment", "source", "Bollywood Hungama"));
            // General
            RSS_FEEDS.put("https://timesofindia.indiatimes.com/rssfeedstopstories.cms", Map.of("category", "General", "source", "Times of India"));
        }
        if (feedsOverride != null && !feedsOverride.isBlank()) {
            RSS_FEEDS.clear();
            Arrays.stream(feedsOverride.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(url -> RSS_FEEDS.put(url, Map.of("category", "General", "source", url)));
        }
    }
    
    // Scheduled method to fetch news articles every hour (3600000 ms = 1 hour)
    @Scheduled(fixedRate = 3600000)
    public void fetchNewsArticles() {
        if (!rssFeedsEnabled) {
            logger.info("RSS feed ingestion disabled via property app.rss.feeds.enabled=false");
            return;
        }
        logger.info("Starting scheduled news fetching process...");
        logger.info("Total RSS feeds configured: {}", RSS_FEEDS.size());
        
        int totalArticlesProcessed = 0;
        int newArticlesSaved = 0;
        int feedsProcessed = 0;
        int feedsFailed = 0;
        
        // Process each RSS feed
        for (Map.Entry<String, Map<String, String>> feedEntry : RSS_FEEDS.entrySet()) {
            String feedUrl = feedEntry.getKey();
            String category = feedEntry.getValue().get("category");
            String source = feedEntry.getValue().get("source");
            
            try {
                logger.info("Fetching news from: {} ({})", feedUrl, source);
                
                // Parse RSS feed using Rome library with enhanced timeout and error handling
                SyndFeedInput input = new SyndFeedInput();
                URI uri = URI.create(feedUrl);
                
                // Create URL connection with timeout and proper headers
                java.net.URLConnection connection = uri.toURL().openConnection();
                connection.setConnectTimeout(10000); // 10 seconds
                connection.setReadTimeout(20000); // 20 seconds (increased for slow feeds)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (NewsAggregator/1.0)");
                connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
                connection.setRequestProperty("Accept-Charset", "UTF-8");
                
                // Create XmlReader with better encoding handling
                XmlReader xmlReader = new XmlReader(connection.getInputStream());
                SyndFeed feed = input.build(xmlReader);
                
                if (feed.getEntries() == null || feed.getEntries().isEmpty()) {
                    logger.warn("No articles found in feed: {}", feedUrl);
                    continue;
                }
                
                logger.info("Processing {} articles from {} ({})", feed.getEntries().size(), source, category);
                feedsProcessed++;
                
                // Process all available articles in feed (cap removed)
                List<SyndEntry> entriesToProcess = feed.getEntries();
                int savedFromThisFeed = 0;
                for (SyndEntry entry : entriesToProcess) {
                    totalArticlesProcessed++;
                    
                    // Skip if article URL already exists in database to prevent duplicates
                    if (articleRepository.existsByUrl(entry.getUri())) {
                        continue;
                    }
                    
                    // Enhanced filtering - Skip visual stories with strict checks
                    if (isEnhancedVisualStory(entry)) {
                        logger.debug("⏭️ Skipping visual story or HTML content: {}", entry.getTitle());
                        continue;
                    }
                    
                    // Create new article entity
                    Article article = new Article();
                    article.setTitle(entry.getTitle());
                    
                    // Get raw description and clean it thoroughly
                    String rawDescription = "";
                    if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
                        rawDescription = entry.getDescription().getValue();
                    }
                    
                    // Use enhanced HTML cleaning to strip ALL HTML tags
                    String cleanedContent = cleanHtml(rawDescription);
                    
                    // Set both summary and content to the cleaned plain text
                    article.setSummary(cleanedContent);
                    article.setContent(cleanedContent);
                    
                    // Use helper method to extract image URL
                    String imageUrl = extractImageUrl(entry);
                    article.setImageUrl(imageUrl);
                    
                    // Log image extraction results for debugging
                    if (imageUrl != null) {
                        logger.debug("✅ Image found for article '{}': {}", 
                                   article.getTitle().length() > 50 ? article.getTitle().substring(0, 50) + "..." : article.getTitle(), 
                                   imageUrl);
                    } else {
                        logger.debug("❌ No image found for article '{}'", 
                                   article.getTitle().length() > 50 ? article.getTitle().substring(0, 50) + "..." : article.getTitle());
                    }
                    
                    article.setSource(source);
                    article.setOriginalSource(source);
                    article.setSourceProvider("RSS");
                    article.setPublishDate(entry.getPublishedDate());
                    article.setUrl(entry.getUri());
                    article.setCategory(category);

                    // Removed lightweight Groq language signal detection to avoid extra API calls.
                    
                    // Generate and persist contextual categories via Groq (repurposed tags field)
                    try {
                        // IMPROVED AI TAGGING SYSTEM: Two-pass approach
                        
                        // FIRST PASS: Generate 3-5 specific tags (free-form discovery)
                        List<String> specificTags = groqService.generateTags(cleanedContent);
                        
                        if (specificTags != null && !specificTags.isEmpty()) {
                            // Store specific tags in domainCategories field (keeping field name for compatibility)
                            article.setDomainCategories(List.copyOf(specificTags));
                            
                            // SECOND PASS: Map specific tags to 1-3 main domain categories with learning
                            List<String> mainDomains = groqService.mapTagsToDomains(specificTags);
                            
                            if (!mainDomains.isEmpty()) {
                                article.setTags(mainDomains);
                                logger.debug("AI Tagging: {} specific tags -> {} domains", specificTags, mainDomains);
                            } else {
                                // Fallback: Try to guess domain from content
                                Optional<String> fallback = TaxonomyUtil.guessBucketFromText(cleanedContent);
                                article.setTags(fallback.map(List::of).orElse(List.of()));
                            }
                        } else {
                            // No tags generated, try fallback
                            Optional<String> fallback = TaxonomyUtil.guessBucketFromText(cleanedContent);
                            article.setTags(fallback.map(List::of).orElse(List.of()));
                            article.setDomainCategories(List.of());
                        }

                        // Reduced throttle with exponential backoff handling rate limits
                        try {
                            Thread.sleep(1000); // Reduced to 1 second - exponential backoff will handle rate limits
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Restore interrupted status
                            logger.warn("Throttling delay was interrupted.", e);
                        }
                    } catch (Exception tagEx) {
                        logger.debug("Tag generation failed: {}", tagEx.getMessage());
                    }

                    // Save article to database (without tags field yet)
                    articleRepository.save(article);
                    newArticlesSaved++;
                    savedFromThisFeed++;
                    
                    logger.debug("Saved new article: {}", article.getTitle());
                }
                
                logger.info("Completed processing {} articles from {} (saved {} new articles)", 
                           entriesToProcess.size(), source, savedFromThisFeed);
                
            } catch (java.net.UnknownHostException e) {
                logger.warn("Network connectivity issue for feed {}: {}", feedUrl, e.getMessage());
                feedsFailed++;
            } catch (java.net.SocketTimeoutException e) {
                logger.warn("Timeout while fetching feed {}: {}", feedUrl, e.getMessage());
                feedsFailed++;
            } catch (javax.net.ssl.SSLHandshakeException e) {
                logger.warn("SSL handshake failed for feed {}: {}", feedUrl, e.getMessage());
                feedsFailed++;
            } catch (java.io.IOException e) {
                if (e.getMessage().contains("Server returned HTTP response code: 403")) {
                    logger.warn("Access forbidden (403) for feed {}: {}", feedUrl, source);
                } else if (e.getMessage().contains("Server returned HTTP response code: 404")) {
                    logger.warn("Feed not found (404) for {}: {}", feedUrl, source);
                } else {
                    logger.warn("Network error for feed {}: {}", feedUrl, e.getMessage());
                }
                feedsFailed++;
            } catch (com.rometools.rome.io.ParsingFeedException e) {
                logger.warn("RSS parsing error for feed {}: {}", feedUrl, e.getMessage());
                feedsFailed++;
            } catch (Exception e) {
                // Handle XML parsing errors (including prolog errors) and other unexpected errors
                if (e.getMessage() != null && e.getMessage().contains("Content is not allowed in prolog")) {
                    logger.warn("Invalid XML format (prolog error) for feed {}: {}. This feed may be returning HTML instead of XML.", feedUrl, source);
                } else {
                    logger.error("Unexpected error processing RSS feed {}: {}", feedUrl, e.getMessage());
                }
                feedsFailed++;
            }
        }
        
        // GNews API disabled - using RSS feeds and Guardian API only
        logger.info("GNews API is disabled (using RSS feeds and Guardian API only)");
        
        // Fetch articles from Guardian API
        try {
            logger.info("Fetching articles from Guardian API...");
            List<Article> guardianArticles = fetchGuardianApiArticlesLimited();
            
            int guardianSaved = 0;
            for (Article article : guardianArticles) {
                try {
                    articleRepository.save(article);
                    guardianSaved++;
                } catch (Exception e) {
                    logger.debug("Failed to save Guardian article: {}", e.getMessage());
                }
            }
            
            logger.info("Guardian API: Saved {} new articles", guardianSaved);
            newArticlesSaved += guardianSaved;
            totalArticlesProcessed += guardianArticles.size();
            
        } catch (Exception e) {
            logger.error("Guardian API fetch failed: {}", e.getMessage());
        }
        
    logger.info("News fetching completed. RSS Feeds: {}/{}, Guardian API: included, Total new articles: {}, Total processed: {}", 
                   feedsProcessed, RSS_FEEDS.size(), newArticlesSaved, totalArticlesProcessed);
        
        if (feedsFailed > 0) {
            logger.warn("Failed to process {} feeds", feedsFailed);
        }
    }
    
    // Manual method to trigger news fetching (useful for testing)
    public void fetchNewsManually() {
        logger.info("Manual news fetch triggered");
        fetchNewsArticles();
    }
    
    // Method to validate a single RSS feed (useful for testing new feeds)
    public boolean validateRssFeed(String feedUrl) {
        try {
            logger.info("Validating RSS feed: {}", feedUrl);
            
            SyndFeedInput input = new SyndFeedInput();
            URI uri = URI.create(feedUrl);
            
            java.net.URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (NewsAggregator/1.0)");
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
            
            XmlReader xmlReader = new XmlReader(connection.getInputStream());
            SyndFeed feed = input.build(xmlReader);
            
            if (feed.getEntries() != null && !feed.getEntries().isEmpty()) {
                logger.info("✅ RSS feed validation successful: {} articles found", feed.getEntries().size());
                return true;
            } else {
                logger.warn("❌ RSS feed validation failed: No articles found");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("❌ RSS feed validation failed for {}: {}", feedUrl, e.getMessage());
            return false;
        }
    }
    
    /**
     * Enhanced HTML cleaning method that strips ALL HTML tags using regex
     * This provides a more robust cleaning than the previous method
     */
    private String cleanHtml(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        
        try {
            // Remove all HTML tags using regex
            String cleanText = html
                .replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "")  // Remove script tags and content
                .replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "")    // Remove style tags and content
                .replaceAll("(?i)<!--[\\s\\S]*?-->", "")                 // Remove HTML comments
                .replaceAll("<[^>]+>", " ")                               // Remove all remaining HTML tags
                .replaceAll("&nbsp;", " ")                                // Replace non-breaking spaces
                .replaceAll("&amp;", "&")                                 // Decode HTML entities
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&apos;", "'")
                .replaceAll("&#\\d+;", "")                                // Remove numeric HTML entities
                .replaceAll("&[a-zA-Z]+;", "")                            // Remove named HTML entities
                .replaceAll("\\s+", " ")                                  // Replace multiple spaces with single space
                .trim();                                                  // Trim leading/trailing whitespace
            
            // Limit to reasonable length for summary
            if (cleanText.length() > 300) {
                // Try to break at sentence boundary
                int lastPeriod = cleanText.indexOf('.', 200);
                if (lastPeriod > 0 && lastPeriod < 350) {
                    cleanText = cleanText.substring(0, lastPeriod + 1);
                } else {
                    cleanText = cleanText.substring(0, 297) + "...";
                }
            }
            
            return cleanText;
            
        } catch (Exception e) {
            logger.debug("Error cleaning HTML: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Enhanced visual story detection with stricter filtering
     * Skips articles with visual story URLs or descriptions starting with HTML tags
     */
    private boolean isEnhancedVisualStory(SyndEntry entry) {
        try {
            // Strict Check 1: URL contains visual story keywords
            if (entry.getUri() != null) {
                String url = entry.getUri().toLowerCase();
                if (url.contains("visualstories") || 
                    url.contains("web-stories") || 
                    url.contains("webstories") ||
                    url.contains("/stories/") ||
                    url.contains("amp-stories") ||
                    url.contains("visual-story")) {
                    return true;
                }
            }
            
            // Strict Check 2: Description starts with HTML link or image tags
            if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
                String description = entry.getDescription().getValue().trim();
                if (description.startsWith("<a href") || 
                    description.startsWith("<img")) {
                    return true;
                }
            }
            
            // Additional checks for other visual content indicators
            if (entry.getTitle() != null) {
                String title = entry.getTitle().toLowerCase();
                if (title.contains("visual story") ||
                    title.contains("web story") ||
                    title.contains("photo story") ||
                    title.startsWith("watch:") ||
                    title.startsWith("video:") ||
                    title.startsWith("photos:")) {
                    return true;
                }
            }
            
        } catch (Exception e) {
            logger.debug("Error checking if entry is enhanced visual story: {}", e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Helper method to extract image URL from RSS entry using multiple techniques
     * Tries in order: media:content, enclosures, HTML parsing
     */
    private String extractImageUrl(SyndEntry entry) {
        try {
            // Method 1: Check for media:content tag (common for high-quality images)
            if (entry.getModules() != null && !entry.getModules().isEmpty()) {
                for (com.rometools.rome.feed.module.Module module : entry.getModules()) {
                    String moduleString = module.toString();
                    if (moduleString.contains("media:content") && moduleString.contains("url=")) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("url=['\"]([^'\"]+)['\"]");
                        java.util.regex.Matcher matcher = pattern.matcher(moduleString);
                        if (matcher.find()) {
                            String imageUrl = matcher.group(1);
                            if (isValidImageUrl(imageUrl)) {
                                logger.debug("Found image via media:content: {}", imageUrl);
                                return imageUrl;
                            }
                        }
                    }
                }
            }
            
            // Method 2: Check for enclosures with image/* type
            if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
                for (com.rometools.rome.feed.synd.SyndEnclosure enclosure : entry.getEnclosures()) {
                    if (enclosure.getType() != null && enclosure.getType().startsWith("image/")) {
                        String imageUrl = enclosure.getUrl();
                        if (isValidImageUrl(imageUrl)) {
                            logger.debug("Found image via enclosure: {}", imageUrl);
                            return imageUrl;
                        }
                    }
                }
            }
            
            // Method 3: Parse the article's description HTML and find the first <img> tag
            if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
                String content = entry.getDescription().getValue();
                java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile(
                    "<img[^>]+src=['\"]([^'\"]+)['\"][^>]*>", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher imgMatcher = imgPattern.matcher(content);
                if (imgMatcher.find()) {
                    String imageUrl = imgMatcher.group(1);
                    if (isValidImageUrl(imageUrl)) {
                        logger.debug("Found image via HTML parsing: {}", imageUrl);
                        return imageUrl;
                    }
                }
            }
            
        } catch (Exception e) {
            logger.debug("Error extracting image URL: {}", e.getMessage());
        }
        
        // Return null if no image found
        return null;
    }
    
    /**
     * Validate if an image URL is properly formatted and likely to work
     */
    private boolean isValidImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return false;
        }
        
        // Clean up the URL
        imageUrl = imageUrl.trim();
        
        // Must start with http or https
        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            return false;
        }
        
        // Must end with image extension or have image extension in path
        if (!imageUrl.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg)(\\?.*)?$")) {
            return false;
        }
        
        // Avoid obviously broken URLs
        if (imageUrl.contains("placeholder") || imageUrl.contains("loading") || 
            imageUrl.contains("spinner") || imageUrl.length() < 20) {
            return false;
        }
        
        return true;
    }

    // ==================== External API Integrations ====================

    /**
     * Fetch limited (page-size=15) articles from The Guardian Content API.
     * Uses show-blocks=body to retrieve full bodyHtml for richer content.
     */
    public List<Article> fetchGuardianApiArticlesLimited() {
        if (guardianApiKey == null || guardianApiKey.isBlank()) {
            logger.warn("Guardian API key missing; cannot fetch Guardian API articles");
            return List.of();
        }
        String url = "https://content.guardianapis.com/search?page-size=15&show-blocks=body&api-key=" + guardianApiKey;
        try {
            ResponseEntity<GuardianApiResponse> resp = restTemplate.getForEntity(url, GuardianApiResponse.class);
            GuardianApiResponse body = resp.getBody();
            if (body == null || body.getResponse() == null || body.getResponse().getResults() == null) {
                logger.warn("Guardian API returned empty response body");
                return List.of();
            }
            List<Article> articles = new java.util.ArrayList<>();
            for (GuardianApiResponse.Result r : body.getResponse().getResults()) {
                try {
                    if (articleRepository.existsByUrl(r.getWebUrl())) continue; // skip duplicates
                    Article a = new Article();
                    a.setTitle(r.getWebTitle());
                    // Robust nested extraction per specification
                    String rawHtml = "[Full article content was not available for this entry]";
                    if (r.getBlocks() != null &&
                        r.getBlocks().getBody() != null &&
                        !r.getBlocks().getBody().isEmpty() &&
                        r.getBlocks().getBody().get(0) != null &&
                        r.getBlocks().getBody().get(0).getBodyHtml() != null) {
                        rawHtml = r.getBlocks().getBody().get(0).getBodyHtml();
                    }
                    String plain = cleanHtmlNoTruncate(rawHtml);
                    a.setContent(rawHtml); // keep full HTML body
                    a.setSummary(deriveSummary(plain, 300));
                    a.setSource("The Guardian (Full Article)");
                    a.setOriginalSource("The Guardian");
                    a.setSourceProvider("Guardian-API");
                    a.setPublishDate(java.util.Date.from(java.time.OffsetDateTime.parse(r.getWebPublicationDate()).toInstant()));
                    a.setUrl(r.getWebUrl());
                    a.setCategory("International");
                    enrichArticleLightweight(a, plain);
                    articleRepository.save(a);
                    articles.add(a);
                } catch (Exception inner) {
                    logger.debug("Skipping Guardian article due to error: {}", inner.getMessage());
                }
            }
            return articles;
        } catch (Exception e) {
            logger.error("Guardian API fetch failed: {}", e.getMessage());
            return List.of();
        }
    }



    // ==================== Helper Methods (New) ====================
    private String cleanHtmlNoTruncate(String html) {
        if (html == null || html.isBlank()) return "";
        try {
            String txt = html
                .replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", " ")
                .replaceAll("(?i)<!--[\\s\\S]*?-->", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&apos;", "'")
                .replaceAll("\\s+", " ")
                .trim();
            return txt;
        } catch (Exception e) { return ""; }
    }

    private String deriveSummary(String full, int maxChars) {
        if (full == null) return "";
        if (full.length() <= maxChars) return full;
        int lastPeriod = full.indexOf('.', Math.min(full.length()-1, maxChars - 40));
        if (lastPeriod > 0 && lastPeriod < maxChars + 60) {
            return full.substring(0, lastPeriod + 1);
        }
        return full.substring(0, maxChars - 3) + "...";
    }

    private void enrichArticleLightweight(Article a, String basis) {
        // Removed bias signal detection. Only generate contextual tags now.
        try {
            List<String> fineTags = groqService.generateTags(basis == null ? "" : basis);
            if (fineTags != null && !fineTags.isEmpty()) {
                a.setDomainCategories(List.copyOf(fineTags));
            }
            List<String> unmapped = fineTags == null ? List.of() : fineTags.stream()
                    .filter(tag -> TaxonomyUtil.bucketForKeyword(tag).isEmpty())
                    .toList();
            // Removed TaxonomyPendingStore for performance (capstone project optimization)
            List<String> bucketTags = new java.util.ArrayList<>(TaxonomyUtil.mapKeywordsToBuckets(fineTags));
            if (bucketTags.isEmpty()) {
                TaxonomyUtil.guessBucketFromText(basis)
                        .ifPresent(bucketTags::add);
            }
            if (!bucketTags.isEmpty()) {
                a.setTags(bucketTags);
            }
        } catch (Exception ignored) {
        }
    }
    
}
