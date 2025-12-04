package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
// Removed legacy BiasAnalysisPrompt usage; inline prompt construction retained.
import com.perplexity.newsaggregator.service.GeminiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

class CachedAnalysis {
    final String json;
    final Instant timestamp;
    CachedAnalysis(String json) { this.json = json; this.timestamp = Instant.now(); }
}

@RestController
@RequestMapping("/api")
public class BiasAnalysisController {
    
    private static final Logger logger = LoggerFactory.getLogger(BiasAnalysisController.class);
    
    @Autowired
    private ArticleRepository articleRepository;
    
    @Autowired
    private GeminiService geminiService;

    // Simple in-memory cache (could be replaced with Caffeine/Redis later)
    private final Map<String, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_SECONDS = 3600; // 1 hour
    private static final int MAX_CACHE_SIZE = 500; // safety bound
    
    // Analyze bias for a specific article using Gemini 2.5 Flash
    @PostMapping("/news/{id}/bias")
    public ResponseEntity<String> analyzeBias(@PathVariable String id,
                                              @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        try {
            logger.info("Starting Gemini 2.5 Flash bias analysis for article ID: {}", id);
            
            // Retrieve article from database using ID
            Optional<Article> articleOptional = articleRepository.findById(id);
            
            if (articleOptional.isEmpty()) {
                logger.warn("Article with ID {} not found", id);
                return ResponseEntity.notFound().build();
            }
            
            Article article = articleOptional.get();
            logger.info("Retrieved article for bias analysis: {}", article.getTitle());
            
            // Validate article content / summary pre-check (graceful early exit)
            String rawContent = article.getContent();
            if (rawContent == null || rawContent.trim().isEmpty()) {
                logger.warn("Article {} has no summary/content for bias analysis", id);
                return ResponseEntity.badRequest()
                    .body("{\"error\": \"Error: No summary available to analyze for bias.\"}");
            }

            // Normalize whitespace & truncate excessively long content (safety)
            String normalizedContent = rawContent.replaceAll("\r?\n+", " \n").trim();
            if (normalizedContent.length() > 12000) { // defensive cap
                normalizedContent = normalizedContent.substring(0, 12000) + "...";
            }
            
            String category = article.getCategory();
            String cacheKey = id + "::" + (category == null ? "" : category.toLowerCase());

            // Persistent cache: prefer stored structuredBiasAnalysis if complete & not forced
            if (!force && article.getStructuredBiasAnalysis() != null) {
                try {
                    com.perplexity.newsaggregator.dto.StructuredBiasAnalysis existing = article.getStructuredBiasAnalysis();
                    String lvlExists = existing.getBiasLevel();
                    boolean lvlValid = lvlExists != null && (lvlExists.equals("Low") || lvlExists.equals("Medium") || lvlExists.equals("High") || lvlExists.equals("Neutral"));
                    boolean hasReason = existing.getReasonForBias() != null && !existing.getReasonForBias().isBlank();
                    boolean hasXaiJustification = existing.getXaiJustification() != null && existing.getXaiJustification().trim().length() > 25; // XAI component is critical
                    if (lvlValid && hasReason && hasXaiJustification) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        String existingJson = mapper.writeValueAsString(existing);
                        logger.debug("Returning persisted structured bias analysis for article {} (validated)", id);
                        return ResponseEntity.ok().header("Content-Type", "application/json").body(addCachedFlag(existingJson));
                    } else {
                        logger.info("Structured bias analysis for article {} incomplete (lvlValid={}, hasReason={}, hasXaiJustification={}) → recomputing", id, lvlValid, hasReason, hasXaiJustification);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed serializing/validating existing structured analysis for article {}: {}", id, ex.getMessage());
                }
            }

            // In-memory ephemeral cache (recent analyses) if not using force
            if (!force) {
                CachedAnalysis cached = analysisCache.get(cacheKey);
                if (cached != null && Instant.now().minusSeconds(CACHE_TTL_SECONDS).isBefore(cached.timestamp)) {
                    logger.debug("Returning cached bias analysis for key {}", cacheKey);
                    return ResponseEntity.ok().header("Content-Type", "application/json").body(addCachedFlag(cached.json));
                }
            }

        // Generate XAI-enhanced structured analysis prompt
        String prompt = "You are a sophisticated media analyst implementing Explainable AI (XAI) principles. Your task is to analyze this article for bias while providing transparent reasoning.\n\n" +
            
            "CRITICAL XAI REQUIREMENT: You must explain HOW you made your decision, not just WHAT you decided. Think step-by-step and be transparent about your reasoning process.\n\n" +
            
            "Before analyzing, consider potential biases in your own training:\n" +
            "- Are you applying Western/American perspectives to non-Western content?\n" +
            "- Could your training data have political or cultural biases?\n" +
            "- Are you being influenced by the source or publication name?\n\n" +
            
            "Provide a JSON response with these fields (keep each field concise - max 2-3 lines):\n\n" +
            
            "biasLevel: One of 'Low', 'Medium', 'High', or 'Neutral'\n\n" +
            
            "confidence: Your confidence in this assessment - 'High', 'Medium', or 'Low'\n\n" +
            
            "reasonForBias: In 2-3 lines, explain WHY you assigned this bias level. Be specific about what you found in the text.\n\n" +
            
            "xaiJustification: [KEY XAI COMPONENT] In 2-3 lines, explain HOW you made this decision. What was your reasoning process? What did you look for? This demonstrates transparency in AI decision-making.\n\n" +
            
            "missingContext: Array of max 3 bullet points identifying missing information or perspectives.\n\n" +
            
            "balancedPerspective: In 2-3 lines, describe how this article could be more balanced.\n\n" +
            
            "selfReflection: In 2-3 lines, acknowledge what biases you might have missed or where you could be wrong.\n\n" +
            
            "REMEMBER: Keep each field concise (2-3 lines max) but informative. Focus on transparency and explainability.\n\n" +
            
            "ARTICLE TITLE: " + article.getTitle() + "\n\nARTICLE CONTENT: " + normalizedContent;
        logger.debug("Generated structured Gemini prompt for article: {} (len={})", article.getTitle(), prompt.length());
            


            
            // Call Gemini 2.5 Pro service to get AI analysis
            // Ensure Gemini request format will contain: { contents: [ { parts: [ { text: prompt } ] } ] }
            logger.debug("Dispatching prompt to Gemini Flash model with length {} chars", prompt.length());
            String aiResponse = geminiService.generateCompletion(prompt);
            logger.debug("[BIAS] Raw AI response length={} first100='{}'", aiResponse != null ? aiResponse.length() : -1, aiResponse != null ? aiResponse.substring(0, Math.min(100, aiResponse.length())).replaceAll("\n"," ") : "null");
            
            // Enhanced response validation
            if (aiResponse == null || aiResponse.trim().isEmpty()) {
                logger.error("Received empty response from Gemini 2.5 Flash for article: {}", article.getTitle());
                return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Empty response from Gemini 2.5 Flash AI service\"}");
            }
            
            // Check if the response contains an error
            if (aiResponse.trim().startsWith("{\"error\":")) {
                logger.warn("Gemini 2.5 Flash returned error response for article '{}': {}", 
                           article.getTitle(), aiResponse);
                // If this is a structured rate limit error, surface it with 429 for clarity
                if (aiResponse.contains("RATE_LIMIT_EXCEEDED")) {
                    return ResponseEntity.status(429)
                        .header("Content-Type", "application/json")
                        .body(aiResponse);
                }
                return ResponseEntity.internalServerError()
                    .header("Content-Type", "application/json")
                    .body(aiResponse);
            }
            
            // Validate strict JSON format
            String normalized = aiResponse.trim();
            if (normalized.startsWith("```") ) {
                normalized = normalized.replaceFirst("```(json|JSON)?", "").replaceAll("```$", "").trim();
            }
            if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
                logger.error("Gemini returned non-JSON despite instruction. First 120: {}", normalized.substring(0, Math.min(120, normalized.length())));
                return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Invalid response format from Gemini (expected JSON object)\"}");
            }

            // Parse into structured DTO to ensure key presence and types
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.perplexity.newsaggregator.dto.StructuredBiasAnalysis structured;
            try {
                structured = mapper.readValue(normalized, com.perplexity.newsaggregator.dto.StructuredBiasAnalysis.class);
            } catch (Exception parseEx) {
                logger.error("Failed to parse structured bias analysis JSON: {}", parseEx.getMessage());
                return ResponseEntity.internalServerError()
                    .body("{\"error\": \"Failed to parse structured bias analysis JSON\"}");
            }

            // Validate biasLevel and confidence
            String lvl = structured.getBiasLevel();
            if (lvl == null || !(lvl.equals("Low") || lvl.equals("Medium") || lvl.equals("High") || lvl.equals("Neutral"))) {
                logger.warn("Invalid biasLevel from AI: {}", lvl);
                if (lvl == null) structured.setBiasLevel("Neutral");
            }
            
            String conf = structured.getConfidence();
            if (conf == null || !(conf.equals("High") || conf.equals("Medium") || conf.equals("Low"))) {
                logger.warn("Invalid confidence from AI: {}", conf);
                if (conf == null) structured.setConfidence("Medium");
            }
            
            // Validate XAI justification exists (key requirement)
            if (structured.getXaiJustification() == null || structured.getXaiJustification().trim().isEmpty()) {
                logger.warn("Missing XAI justification - this is required for transparency");
                structured.setXaiJustification("AI reasoning process not provided - this analysis may lack transparency.");
            }

            // Persist structured JSON as unified string for FE, and optionally map to existing field if needed
            String normalizedOut;
            try {
                normalizedOut = mapper.writeValueAsString(structured);
            } catch (Exception ex) {
                normalizedOut = normalized; // fallback raw
            }

            // Persist on Article (Option A)
            article.setStructuredBiasAnalysis(structured);
            try { articleRepository.save(article); } catch (Exception saveEx) {
                logger.warn("Failed to persist structured bias analysis: {}", saveEx.getMessage());
            }

            logger.info("Successfully received structured bias analysis for article: {}", article.getTitle());

            // Cache result (basic eviction if size exceeded)
            if (analysisCache.size() >= MAX_CACHE_SIZE) {
                analysisCache.clear();
                logger.info("Bias analysis cache cleared (size limit exceeded)");
            }
            analysisCache.put(cacheKey, new CachedAnalysis(normalizedOut));

            return ResponseEntity.ok().header("Content-Type", "application/json").body(normalizedOut);
            
        } catch (Exception e) {
            logger.error("Error during Gemini 2.5 Flash bias analysis for article ID {}: {}", id, e.getMessage(), e);
            String errorMessage = e.getMessage().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            return ResponseEntity.internalServerError()
                .body("{\"error\": \"Failed to analyze article bias with Gemini 2.5 Flash: " + errorMessage + "\"}");
        }
    }
    
    // Health check endpoint for Gemini 2.5 Flash bias analysis service
    @GetMapping("/bias/health")
    public ResponseEntity<String> healthCheck() {
        try {
            // Test basic Gemini 2.5 Flash connectivity
                String testPrompt = "Please respond with valid JSON: {\\\"status\\\": \\\"ok\\\", \\\"model\\\": \\\"gemini-2.5-flash\\\"}";
            String testResponse = geminiService.generateCompletion(testPrompt);
            
            if (testResponse != null && testResponse.contains("ok")) {
                return ResponseEntity.ok("{\"status\": \"Gemini 2.5 Flash bias analysis service is running\", \"model\": \"gemini-2.5-flash\"}");
            } else {
                return ResponseEntity.internalServerError()
                    .body("{\"status\": \"Gemini 2.5 Flash service connection failed\", \"response\": \"" + testResponse + "\"}");
            }
        } catch (Exception e) {
            logger.error("Health check failed for Gemini 2.5 Flash service: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body("{\"status\": \"Gemini 2.5 Flash service error\", \"error\": \"" + e.getMessage() + "\"}");
        }
    }
    private String addCachedFlag(String json) {
        if (json == null || json.isEmpty()) return json;
        try {
            // If already contains cached flag, return
            if (json.contains("\"cached\"")) return json;
            // Insert at root: parse and reserialize
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            node.put("cached", true);
            return mapper.writeValueAsString(node);
        } catch(Exception e) {
            return json; // fail-open
        }
    }
}
