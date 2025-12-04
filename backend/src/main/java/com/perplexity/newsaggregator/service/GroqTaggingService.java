package com.perplexity.newsaggregator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perplexity.newsaggregator.util.LoggingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service responsible for AI-powered article tagging using Groq API.
 * Handles tag generation and domain mapping with rate limiting and key rotation.
 */
@Service
public class GroqTaggingService {
    private static final Logger log = LoggerFactory.getLogger(GroqTaggingService.class);

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.1-8b-instant";

    @Value("${groq.api.key:}")
    private String apiKey;
    
    @Value("${groq.api.key.backup1:}")
    private String apiKeyBackup1;
    
    @Value("${groq.api.key.backup2:}")
    private String apiKeyBackup2;
    
    @Value("${app.tagging.confidence-threshold:0.6}")
    private double confidenceThreshold;
    
    // Round-robin key selection for load balancing
    private volatile int currentKeyIndex = 0;
    private final Object keyLock = new Object();

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    
    // Rate limiting and retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 15000; // 15 seconds base delay (4 calls/min max)
    private static final long MAX_DELAY_MS = 60000; // 60 seconds max delay
    private volatile long lastRequestTime = 0;
    private volatile int consecutiveErrors = 0;
    
    /**
     * Get next available Groq API key using round-robin strategy
     */
    private String getNextGroqApiKey() {
        synchronized (keyLock) {
            String[] keys = {apiKey, apiKeyBackup1, apiKeyBackup2};
            
            // Filter out null/empty keys
            List<String> validKeys = java.util.Arrays.stream(keys)
                .filter(key -> key != null && !key.trim().isEmpty())
                .collect(Collectors.toList());
            
            if (validKeys.isEmpty()) {
                LoggingUtil.logError(log, "API Key Selection", "No valid Groq API keys configured");
                return null;
            }
            
            String selectedKey = validKeys.get(currentKeyIndex % validKeys.size());
            currentKeyIndex = (currentKeyIndex + 1) % validKeys.size();
            
            // Mask key for logging
            String maskedKey = selectedKey.length() > 10 ? 
                selectedKey.substring(0, 6) + "***" + selectedKey.substring(selectedKey.length() - 4) : 
                "***";
            
            LoggingUtil.logRateLimit(log, "Groq", "Key selected", 
                "key", maskedKey, "index", currentKeyIndex - 1, "available", validKeys.size());
            
            return selectedKey;
        }
    }

    /**
     * IMPROVED AI TAGGING SYSTEM - FIRST PASS:
     * Generate 3-5 specific tags from article content (free-form, can discover new keywords)
     * Returns list of specific tags that will be mapped to domain categories in second pass.
     */
    public List<String> generateTags(String content) {
        LoggingUtil.PerformanceTimer timer = LoggingUtil.startTimer();
        
        if (content == null || content.trim().isEmpty()) {
            LoggingUtil.logWarning(log, "Tag Generation", "Empty content provided");
            return Collections.emptyList();
        }
        if (apiKey == null || apiKey.isBlank()) {
            LoggingUtil.logError(log, "Tag Generation", "Groq API key missing");
            return Collections.emptyList();
        }

        // Get allowed taxonomy for validation (both main categories and fine-grained keywords)
        List<String> allowed = new ArrayList<>();
        allowed.addAll(TaxonomyUtil.all()); // Main categories like "Artificial Intelligence"
        allowed.addAll(TaxonomyUtil.allFineGrained()); // Fine-grained keywords
        String taxonomyList = String.join(", ", allowed);

        // CONSERVATIVE: Precise tag count based on article length to avoid hallucination
        int wordCount = content.split("\\s+").length;
        int targetTagCount;
        
        if (wordCount < 30) {
            // Very short articles: No tags to avoid hallucination
            LoggingUtil.logAITagging(log, "Article too short for tagging", 
                "wordCount", wordCount, "targetTags", 0);
            return Collections.emptyList();
        } else if (wordCount >= 30 && wordCount <= 50) {
            targetTagCount = 1; // Short articles: 1 precise tag
        } else if (wordCount <= 100) {
            targetTagCount = 2; // Small articles: 2 tags
        } else if (wordCount <= 200) {
            targetTagCount = 3; // Medium articles: 3 tags
        } else if (wordCount <= 500) {
            targetTagCount = 4; // Large articles: 4 tags
        } else if (wordCount <= 1000) {
            targetTagCount = 6; // Very large articles: 6 tags
        } else {
            targetTagCount = 8; // Huge articles: max 8 tags (reduced from 25)
        }
        
        LoggingUtil.logAITagging(log, "Starting conservative tag generation", 
            "wordCount", wordCount, "targetTags", targetTagCount);
        
        String prompt = buildTagGenerationPrompt(targetTagCount, content);

        try {
            // Build request body once (payload reused for retry)
            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                mapper.createObjectNode()
                    .put("role", "user")
                    .put("content", prompt)
            ));
            root.put("max_tokens", Math.max(256, targetTagCount * 15)); // Dynamic token limit
            root.put("temperature", 0.3);
            String bodyJson = mapper.writeValueAsString(root);

            try {
                return executeWithExponentialBackoff((currentKey) -> {
                    // Create headers with current key
                    HttpHeaders currentHeaders = new HttpHeaders();
                    currentHeaders.setContentType(MediaType.APPLICATION_JSON);
                    currentHeaders.setBearerAuth(currentKey.trim());
                    HttpEntity<String> currentEntity = new HttpEntity<>(bodyJson, currentHeaders);
                    
                    ResponseEntity<String> response = restTemplate.exchange(
                            GROQ_CHAT_URL,
                            HttpMethod.POST,
                            currentEntity,
                            String.class
                    );
                    
                    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                        throw new RuntimeException("Groq API error: " + response.getStatusCode());
                    }
                    
                    JsonNode responseRoot;
                    try {
                        responseRoot = mapper.readTree(response.getBody());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        throw new RuntimeException("JSON parsing error: " + jpe.getMessage());
                    }
                    JsonNode choices = responseRoot.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        throw new RuntimeException("Groq response missing choices");
                    }
                    
                    String raw = choices.get(0).path("message").path("content").asText("").trim();
                    String jsonArray = extractJsonArray(raw);
                    if (jsonArray.isEmpty()) return Collections.emptyList();
                    
                    List<String> tags;
                    try {
                        tags = mapper.readValue(jsonArray, new TypeReference<List<String>>() {});
                        tags.replaceAll(t -> t == null ? "" : t.trim());
                        tags.removeIf(String::isEmpty);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        throw new RuntimeException("JSON parsing error: " + jpe.getMessage());
                    }
                    
                    // Store original tags for pending taxonomy processing
                    List<String> originalTags = new ArrayList<>(tags);
                    
                    // Validate strictly against allowed taxonomy (exact match)
                    tags = tags.stream().filter(allowed::contains).distinct().toList();
                    
                    // Identify and add unmapped keywords to pending taxonomy for manual review
                    List<String> unmappedKeywords = originalTags.stream()
                        .filter(tag -> !allowed.contains(tag))
                        .distinct()
                        .toList();
                    
                    if (!unmappedKeywords.isEmpty()) {
                        log.info("[TAXONOMY] Adding {} new keywords to pending taxonomy: {}", 
                            unmappedKeywords.size(), unmappedKeywords);
                        
                        for (String unmappedKeyword : unmappedKeywords) {
                            TaxonomyUtil.addToPendingTaxonomy(unmappedKeyword);
                        }
                    }
                    
                    // Apply confidence-based filtering using multiplicative scoring
                    tags = filterByConfidence(tags, content);
                    
                    if (tags.isEmpty()) {
                        LoggingUtil.logWarning(log, "Tag Generation", "No keywords matched taxonomy - article will have no domain tags");
                        // IMPORTANT: Return empty list instead of guessing - better no tag than wrong tag
                        return Collections.emptyList();
                    }
                    
                    // Enforce strict tag count limits - never exceed target
                    if (tags.size() > targetTagCount) {
                        tags = tags.subList(0, targetTagCount);
                        LoggingUtil.logWarning(log, "Tag Generation", "Truncated excess tags", 
                            "returned", tags.size() + targetTagCount - tags.size(), "kept", tags.size());
                    }
                    
                    consecutiveErrors = 0; // Reset error count on success
                    
                    timer.logCompletion(log, "Tag generation", 
                        "tagsGenerated", tags.size(), "targetTags", targetTagCount);
                    LoggingUtil.logSuccess(log, "Tag generation completed", 
                        "tags", tags, "wordCount", wordCount);
                    
                    return tags;
                });
            } catch (Exception groqError) {
                LoggingUtil.logError(log, "Tag generation", "All Groq API keys exhausted", 
                    "error", groqError.getMessage(), "duration", timer.getDurationMs());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Groq contextual categorization failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * IMPROVED AI TAGGING SYSTEM - SECOND PASS:
     * Map specific tags to main domain categories and auto-learn new tag mappings
     * Returns list of 1-3 main domain categories for the article
     */
    public List<String> mapTagsToDomains(List<String> specificTags) {
        if (specificTags == null || specificTags.isEmpty()) return Collections.emptyList();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Groq API key missing; using fallback domain mapping.");
            return TaxonomyUtil.mapTagsToDomainsWithLearning(specificTags);
        }

        List<String> mainDomains = TaxonomyUtil.all();
        String domainsText = String.join(", ", mainDomains);
        String tagsText = String.join(", ", specificTags);
        
        String prompt = buildDomainMappingPrompt(domainsText, tagsText);

        try {
            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                mapper.createObjectNode()
                    .put("role", "user")
                    .put("content", prompt)
            ));
            root.put("max_tokens", 128);
            root.put("temperature", 0.2); // Lower temperature for more consistent mapping
            String bodyJson = mapper.writeValueAsString(root);

            return executeWithExponentialBackoff((currentKey) -> {
                // Create headers with current key
                HttpHeaders currentHeaders = new HttpHeaders();
                currentHeaders.setContentType(MediaType.APPLICATION_JSON);
                currentHeaders.setBearerAuth(currentKey.trim());
                HttpEntity<String> currentEntity = new HttpEntity<>(bodyJson, currentHeaders);
                
                ResponseEntity<String> response = restTemplate.exchange(
                    GROQ_CHAT_URL, HttpMethod.POST, currentEntity, String.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("Groq API error: " + response.getStatusCode());
                }
                
                JsonNode responseJson;
                try {
                    responseJson = mapper.readTree(response.getBody());
                } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                    throw new RuntimeException("JSON parsing error: " + jpe.getMessage());
                }
                var choices = responseJson.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    throw new RuntimeException("Groq response missing choices");
                }
                
                var content = choices.get(0).path("message").path("content").asText();
                if (content.isBlank()) {
                    throw new RuntimeException("Empty response content");
                }
                
                String jsonArray = extractJsonArray(content);
                if (jsonArray.isEmpty()) {
                    throw new RuntimeException("No JSON array found in response");
                }
                
                List<String> domains;
                try {
                    domains = mapper.readValue(jsonArray, new TypeReference<List<String>>() {});
                } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                    throw new RuntimeException("JSON parsing error: " + jpe.getMessage());
                }
                
                List<String> validDomains = domains.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(domain -> mainDomains.contains(domain))
                    .limit(3)
                    .collect(Collectors.toList());
                
                // Auto-learn: Add new tag mappings to taxonomy
                TaxonomyUtil.learnTagMappings(specificTags, validDomains);
                
                return validDomains;
            });
        } catch (Exception e) {
            log.warn("All Groq API keys failed for domain mapping: {}", e.getMessage());
        }
        
        // Fallback to rule-based mapping with learning
        return TaxonomyUtil.mapTagsToDomainsWithLearning(specificTags);
    }

    private String buildTagGenerationPrompt(int targetTagCount, String content) {
        return "You are a precise news classifier. Analyze this article and extract EXACTLY " + targetTagCount + " SPECIFIC, FACTUAL keywords/phrases that accurately represent the MAIN topics mentioned. Be conservative and precise - only tag what is clearly present in the content.\n\n" +
                
                "CRITICAL RULES:\n" +
                "• ONLY tag what is explicitly mentioned in the article\n" +
                "• DO NOT infer or assume topics not directly stated\n" +
                "• DO NOT add generic or vague terms\n" +
                "• Focus on the PRIMARY subject matter only\n" +
                "• Be factual and conservative - avoid speculation\n\n" +
                
                "CRITICAL DISAMBIGUATION RULES - Always add context for ambiguous terms:\n" +
                "• 'Apple' → 'Apple Company' (tech/business) OR 'Apple Fruit' (agriculture)\n" +
                "• 'Jaguar' → 'Jaguar Automobile' (cars) OR 'Jaguar Animal' (wildlife)\n" +
                "• 'Amazon' → 'Amazon Company' (tech/business) OR 'Amazon Rainforest' (environment)\n" +
                "• 'Tesla' → 'Tesla Company' (cars/business) OR 'Tesla Scientist' (historical)\n" +
                "• 'Mercury' → 'Mercury Planet' (space) OR 'Mercury Element' (chemistry)\n" +
                "• 'Mars' → 'Mars Planet' (space) OR 'Mars Company' (food/business)\n" +
                "• 'Oracle' → 'Oracle Company' (tech) OR 'Oracle Database' (software)\n" +
                "• 'Meta' → 'Meta Company' (tech/social media) OR 'Meta Analysis' (research)\n" +
                "• 'Shell' → 'Shell Company' (oil/energy) OR 'Shell Programming' (tech)\n" +
                "• 'Corona' → 'Corona Virus' (health) OR 'Corona Beer' (business)\n" +
                "• 'Delta' → 'Delta Airlines' (travel) OR 'Delta Variant' (health) OR 'Delta Region' (geography)\n" +
                "• 'Phoenix' → 'Phoenix City' (geography) OR 'Phoenix Spacecraft' (space)\n" +
                "• 'Titan' → 'Titan Moon' (space) OR 'Titan Company' (business)\n" +
                "• 'Zoom' → 'Zoom Company' (tech/communication) OR 'Camera Zoom' (photography)\n" +
                "• 'Windows' → 'Microsoft Windows' (tech/software) OR 'Building Windows' (architecture)\n\n" +
                
                "FORMATTING RULES:\n" +
                "• Use Title Case: 'Neural Networks' not 'neural networks'\n" +
                "• Include full context: 'Apple iPhone Launch' not just 'iPhone'\n" +
                "• Proper nouns with context: 'Google DeepMind', 'NASA Mars Mission'\n" +
                "• Specific events: 'World Cup 2024', 'COP28 Climate Summit', 'Apple WWDC 2024'\n" +
                "• Technology with context: 'ChatGPT AI Model', 'Tesla Autopilot System'\n\n" +
                
                "PRIORITIZE (in order of importance):\n" +
                "1. Specific company/organization names: 'Apple', 'Tesla', 'NASA'\n" +
                "2. Concrete technologies/products: 'iPhone', 'ChatGPT', 'Model S'\n" +
                "3. Specific people (if central to story): 'Elon Musk', 'Tim Cook'\n" +
                "4. Precise locations (if relevant): 'Silicon Valley', 'New York'\n" +
                "5. Specific events/concepts: 'IPO', 'Merger', 'Clinical Trial'\n\n" +
                
                "STRICTLY AVOID:\n" +
                "✗ Generic terms: 'technology', 'innovation', 'development', 'growth', 'news'\n" +
                "✗ Vague descriptors: 'advanced', 'cutting-edge', 'revolutionary', 'breakthrough'\n" +
                "✗ Time references: 'today', 'recently', 'soon', 'latest', 'new'\n" +
                "✗ Opinion words: 'amazing', 'incredible', 'fantastic', 'great'\n" +
                "✗ Inferring topics not explicitly mentioned\n" +
                "✗ Adding context not present in the article\n\n" +
                
                "EXAMPLES FOR DIFFERENT ARTICLE LENGTHS:\n\n" +
                
                "Short article (30-50 words) about Apple earnings:\n" +
                "Good: ['Apple']\n" +
                "Bad: ['technology', 'earnings', 'financial performance']\n\n" +
                
                "Medium article (100-200 words) about Tesla launch:\n" +
                "Good: ['Tesla', 'Model Y', 'Electric Vehicle']\n" +
                "Bad: ['automotive', 'innovation', 'sustainable transport', 'cutting-edge']\n\n" +
                
                "Large article (500+ words) about AI research:\n" +
                "Good: ['OpenAI', 'GPT-4', 'Machine Learning', 'Natural Language Processing']\n" +
                "Bad: ['artificial intelligence', 'technology', 'breakthrough', 'revolutionary', 'advanced']\n\n" +
                
                "RESPONSE FORMAT:\n" +
                "Return ONLY a flat JSON array of EXACTLY " + targetTagCount + " strings. No commentary, no explanations, no markdown.\n" +
                "Format: [\"tag1\", \"tag2\", \"tag3\"]\n\n" +
                "Article Text: " + content;
    }

    private String buildDomainMappingPrompt(String domainsText, String tagsText) {
        return "You are an expert content categorizer with deep domain knowledge across all industries. Given these specific tags from a news article, map them to 1-3 most relevant MAIN DOMAIN CATEGORIES from the provided list.\n\n" +
                
                "COMPREHENSIVE DISAMBIGUATION RULES:\n\n" +
                
                "TECHNOLOGY & BUSINESS:\n" +
                "• 'Apple Company', 'iPhone', 'MacBook', 'iOS' → Technology OR Corporate News\n" +
                "• 'Google Company', 'Android', 'Search Engine' → Technology OR Corporate News\n" +
                "• 'Microsoft Corporation', 'Windows', 'Azure Cloud' → Technology OR Corporate News\n" +
                "• 'Tesla Company', 'Electric Vehicle', 'Autopilot' → Automobiles OR Clean Energy\n" +
                "• 'Amazon Company', 'AWS Cloud', 'E-commerce' → Technology OR Corporate News\n" +
                "• 'Meta Company', 'Facebook', 'Instagram', 'VR' → Technology OR Corporate News\n" +
                "• 'Netflix Streaming', 'Disney Plus' → Movies & TV OR Corporate News\n" +
                "• 'Cryptocurrency', 'Bitcoin', 'Ethereum', 'Blockchain' → Cryptocurrency\n" +
                "• 'AI Model', 'Machine Learning', 'Neural Networks' → Artificial Intelligence\n\n" +
                
                "SPACE & SCIENCE:\n" +
                "• 'NASA', 'SpaceX', 'Rocket Launch', 'Mars Mission' → Space & Astronomy\n" +
                "• 'James Webb Telescope', 'Hubble', 'Exoplanet' → Space & Astronomy\n" +
                "• 'Climate Change', 'Global Warming', 'Carbon Emissions' → Climate & Environment\n" +
                "• 'Medical Research', 'Clinical Trial', 'Drug Discovery' → Medical Research\n" +
                "• 'Gene Editing', 'CRISPR', 'Biotechnology' → Biotechnology\n\n" +
                
                "FINANCE & BUSINESS:\n" +
                "• 'Stock Market', 'NYSE', 'NASDAQ', 'Trading' → Stock Market\n" +
                "• 'Federal Reserve', 'Interest Rates', 'Inflation' → Economy\n" +
                "• 'Merger', 'Acquisition', 'IPO', 'Earnings' → Corporate News\n" +
                "• 'Startup Funding', 'Venture Capital', 'Unicorn' → Startups & Venture Capital\n" +
                "• 'Real Estate Market', 'Housing Prices', 'Mortgage' → Real Estate\n\n" +
                
                "POLITICS & GOVERNANCE:\n" +
                "• 'Indian Parliament', 'Modi Government', 'Delhi Politics' → Indian Politics\n" +
                "• 'US Election', 'Biden Administration', 'Congress' → International Politics\n" +
                "• 'Supreme Court India', 'High Court', 'Legal Reform' → Law & Justice\n" +
                "• 'Government Policy', 'Budget 2024', 'Public Scheme' → Government Policy\n" +
                "• 'Human Rights', 'Civil Liberties', 'Gender Equality' → Human Rights\n\n" +
                
                "HEALTH & ENVIRONMENT:\n" +
                "• 'COVID-19', 'Pandemic', 'Vaccine Research' → Public Health\n" +
                "• 'Cancer Research', 'Drug Trial', 'Medical Breakthrough' → Medical Research\n" +
                "• 'Wildlife Conservation', 'Endangered Species', 'Forest' → Wildlife & Nature\n" +
                "• 'Solar Power', 'Wind Energy', 'Renewable Energy' → Clean Energy\n\n" +
                
                "ENTERTAINMENT & SPORTS:\n" +
                "• 'Bollywood', 'Hollywood', 'Movie Release', 'Netflix Series' → Movies & TV\n" +
                "• 'Cricket Match', 'IPL', 'World Cup Cricket' → Cricket\n" +
                "• 'Football Match', 'Premier League', 'FIFA World Cup' → Football\n" +
                "• 'Tennis Tournament', 'Wimbledon', 'US Open' → Tennis\n" +
                "• 'Formula 1', 'F1 Race', 'Grand Prix' → Motorsport\n" +
                "• 'Olympics 2024', 'Olympic Games', 'Medal Tally' → Olympics\n\n" +
                
                "AUTOMOBILES & TRANSPORT:\n" +
                "• 'Tesla Model', 'BMW', 'Mercedes', 'Car Launch' → Automobiles\n" +
                "• 'Electric Vehicle', 'EV Battery', 'Charging Station' → Automobiles OR Clean Energy\n" +
                "• 'Autonomous Driving', 'Self-Driving Car' → Automobiles OR Artificial Intelligence\n\n" +
                
                "NATURE & ANIMALS:\n" +
                "• 'Jaguar Animal', 'Tiger Conservation', 'Wildlife' → Wildlife & Nature\n" +
                "• 'Amazon Rainforest', 'Deforestation', 'Biodiversity' → Wildlife & Nature\n\n" +
                
                "MAPPING STRATEGY:\n" +
                "1. Count tag matches per domain - prioritize domains with most matches\n" +
                "2. Consider tag context and relationships\n" +
                "3. If tags span multiple domains, include up to 3 most relevant\n" +
                "4. Business news about tech companies can be Technology OR Corporate News\n" +
                "5. Environmental tech can be Clean Energy OR Climate & Environment\n" +
                "6. AI in specific industries can be Artificial Intelligence + industry domain\n\n" +
                
                "Available Main Domains: [" + domainsText + "]\n" +
                "Specific Tags to Analyze: [" + tagsText + "]\n\n" +
                
                "Analyze the tags, apply disambiguation rules, count domain matches, and return ONLY a flat JSON array of 1-3 most relevant domain category strings. Format: [\"domain1\", \"domain2\"]. No nested arrays, no commentary.";
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return ""; // signal failure
    }

    /**
     * Calculate multiplicative confidence score for a keyword based on content presence and relevance
     * Formula: confidence = word_present_in_content * relevance_to_context
     */
    private double calculateConfidenceScore(String keyword, String content) {
        if (keyword == null || content == null || keyword.trim().isEmpty() || content.trim().isEmpty()) {
            return 0.0;
        }
        
        String lowerKeyword = keyword.toLowerCase();
        String lowerContent = content.toLowerCase();
        
        // Calculate word presence score (0.0 to 1.0)
        double wordPresenceScore;
        if (lowerContent.contains(lowerKeyword)) {
            // Exact match gets highest score
            wordPresenceScore = 1.0;
        } else {
            // Check for partial matches (individual words from the keyword)
            String[] keywordWords = lowerKeyword.split("\\s+");
            int matchedWords = 0;
            for (String word : keywordWords) {
                if (lowerContent.contains(word)) {
                    matchedWords++;
                }
            }
            // Partial match score based on percentage of words found
            wordPresenceScore = keywordWords.length > 0 ? (double) matchedWords / keywordWords.length * 0.8 : 0.0;
        }
        
        // Calculate relevance score based on keyword specificity and context
        double relevanceScore = calculateRelevanceScore(keyword, content);
        
        // Multiplicative confidence score
        double confidenceScore = wordPresenceScore * relevanceScore;
        
        LoggingUtil.logAITagging(log, "Confidence calculation", 
            "keyword", keyword, "wordPresence", wordPresenceScore, 
            "relevance", relevanceScore, "confidence", confidenceScore);
        
        return confidenceScore;
    }
    
    /**
     * Calculate relevance score based on keyword specificity and semantic context
     */
    private double calculateRelevanceScore(String keyword, String content) {
        if (keyword == null || content == null) return 0.0;
        
        String lowerKeyword = keyword.toLowerCase();
        
        // Higher relevance for specific, non-generic terms
        double relevanceScore = 0.7; // Base relevance
        
        // Boost for proper nouns and specific entities
        if (Character.isUpperCase(keyword.charAt(0))) {
            relevanceScore += 0.2;
        }
        
        // Boost for compound terms (more specific)
        if (keyword.contains(" ") || keyword.contains("-")) {
            relevanceScore += 0.1;
        }
        
        // Penalty for generic terms
        String[] genericTerms = {"news", "update", "report", "article", "story", "latest", "new", "recent",
            "technology", "innovation", "development", "growth", "business", "company", "industry", "market"};
        
        for (String generic : genericTerms) {
            if (lowerKeyword.contains(generic)) {
                relevanceScore -= 0.3;
                break;
            }
        }
        
        // Ensure score stays within bounds
        return Math.max(0.0, Math.min(1.0, relevanceScore));
    }
    
    /**
     * Filter keywords based on confidence threshold
     */
    private List<String> filterByConfidence(List<String> keywords, String content) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> filteredKeywords = keywords.stream()
            .filter(keyword -> {
                double confidence = calculateConfidenceScore(keyword, content);
                boolean meetsThreshold = confidence >= confidenceThreshold;
                
                if (!meetsThreshold) {
                    LoggingUtil.logWarning(log, "Keyword filtered by confidence", 
                        "keyword", keyword, "confidence", confidence, "threshold", confidenceThreshold);
                }
                
                return meetsThreshold;
            })
            .collect(Collectors.toList());
        
        LoggingUtil.logAITagging(log, "Confidence filtering completed", 
            "originalCount", keywords.size(), "filteredCount", filteredKeywords.size(), 
            "threshold", confidenceThreshold);
        
        return filteredKeywords;
    }

    // Very lightweight heuristic: pick first taxonomy category whose key term appears in content
    private String heuristicBroadCategory(String content, List<String> allowed) {
        if (content == null) return null;
        String lower = content.toLowerCase();
        for (String cat : allowed) {
            String core = cat.split(" ")[0].toLowerCase();
            if (lower.contains(core)) return cat;
        }
        return allowed.isEmpty() ? null : allowed.get(0);
    }
    
    /**
     * Execute API call with exponential backoff, rate limiting, and key rotation
     * Each retry attempt uses a DIFFERENT API key for better load distribution
     */
    private <T> T executeWithExponentialBackoff(java.util.function.Function<String, T> apiCall) {
        // Rate limiting: ensure minimum delay between requests
        long now = System.currentTimeMillis();
        long timeSinceLastRequest = now - lastRequestTime;
        long minDelay = BASE_DELAY_MS + (consecutiveErrors * 5000); // Increase delay significantly with errors
        
        if (timeSinceLastRequest < minDelay) {
            long sleepTime = minDelay - timeSinceLastRequest;
            try {
                log.debug("Rate limiting: sleeping {}ms", sleepTime);
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during rate limiting", e);
            }
        }
        
        lastRequestTime = System.currentTimeMillis();
        
        // Try each available key once (round-robin through all keys)
        String[] allKeys = {apiKey, apiKeyBackup1, apiKeyBackup2};
        List<String> validKeys = java.util.Arrays.stream(allKeys)
            .filter(key -> key != null && !key.trim().isEmpty())
            .collect(Collectors.toList());
        
        if (validKeys.isEmpty()) {
            throw new RuntimeException("No valid Groq API keys available");
        }
        
        // Try each key once before giving up
        int totalAttempts = Math.min(MAX_RETRIES, validKeys.size());
        
        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            String currentKey = getNextGroqApiKey(); // This rotates to next key
            if (currentKey == null) {
                throw new RuntimeException("No valid Groq API keys available");
            }
            
            try {
                T result = apiCall.apply(currentKey);
                consecutiveErrors = 0; // Reset on success
                log.info("Groq API call succeeded with key attempt {}/{}", attempt + 1, totalAttempts);
                return result;
            } catch (Exception e) {
                consecutiveErrors++;
                
                // Check for organization restriction error (permanent failure)
                boolean isOrgRestricted = e.getMessage() != null && 
                    e.getMessage().contains("organization_restricted");
                
                if (isOrgRestricted) {
                    log.warn("Groq key has organization restriction (attempt {}/{}), trying next key immediately", 
                        attempt + 1, totalAttempts);
                    // Don't wait for org restriction errors, just try next key
                    continue;
                }
                
                if (attempt == totalAttempts - 1) {
                    log.error("Groq API failed after {} attempts with all {} keys: {}", 
                        totalAttempts, validKeys.size(), e.getMessage());
                    throw new RuntimeException("Max retries exceeded with all Groq keys", e);
                }
                
                // Check if it's a rate limit error
                boolean isRateLimit = e.getMessage() != null && 
                    (e.getMessage().contains("429") || e.getMessage().contains("rate limit"));
                
                if (isRateLimit) {
                    log.warn("Groq key hit rate limit (attempt {}/{}), trying next key after short delay", 
                        attempt + 1, totalAttempts);
                    // For rate limits, add a short delay before trying next key
                    try {
                        Thread.sleep(2000); // 2 second delay for rate limits
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during rate limit backoff", ie);
                    }
                    continue;
                }
                
                // For other errors, use exponential backoff
                long delay = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
                log.warn("Groq API attempt {}/{} failed: {}. Retrying in {}ms with next key", 
                    attempt + 1, totalAttempts, e.getMessage(), delay);
                
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
        
        throw new RuntimeException("Should not reach here");
    }
}