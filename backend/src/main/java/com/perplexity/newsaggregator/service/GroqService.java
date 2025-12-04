package com.perplexity.newsaggregator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Main GroqService that delegates to specialized services for different AI operations.
 * This service acts as a facade and maintains backward compatibility.
 */
@Service
public class GroqService {
    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    @Autowired
    private GroqTaggingService groqTaggingService;
    
    @Autowired
    private GroqBriefingService groqBriefingService;

    @EventListener(ContextRefreshedEvent.class)
    void logKeyStatus() {
        log.info("GroqService initialized - delegating to specialized services");
    }

    // ================= Tagging Operations (Delegated) =================
    
    /**
     * IMPROVED AI TAGGING SYSTEM - FIRST PASS:
     * Generate 3-5 specific tags from article content (free-form, can discover new keywords)
     * Returns list of specific tags that will be mapped to domain categories in second pass.
     */
    public List<String> generateTags(String content) {
        return groqTaggingService.generateTags(content);
    }

    /**
     * IMPROVED AI TAGGING SYSTEM - SECOND PASS:
     * Map specific tags to main domain categories and auto-learn new tag mappings
     * Returns list of 1-3 main domain categories for the article
     */
    public List<String> mapTagsToDomains(List<String> specificTags) {
        return groqTaggingService.mapTagsToDomains(specificTags);
    }

    // Domain category generation deprecated under unified taxonomy approach.
    public List<String> generateDomainCategories(String content) { 
        return Collections.emptyList(); 
    }

    // ================= Briefing Operations (Delegated) =================

    /**
     * Single-shot generic chat invocation (used by dynamic bias analysis meta-prompt).
     * Returns raw model message content. Throws exception on any non-success so caller can trigger fallback.
     */
    public String singleShotChat(String prompt, int maxTokens, double temperature) throws Exception {
        return groqBriefingService.singleShotChat(prompt, maxTokens, temperature);
    }

    /**
     * Generate a one-sentence analyst style daily briefing given user interests and key topics.
     */
    public String generateDailyBriefingSentence(String interestsCsv, String keyTopicsCsv) {
        return groqBriefingService.generateDailyBriefingSentence(interestsCsv, keyTopicsCsv);
    }

    /**
     * Extended variant adding optional language (ISO 639-1) and daypart (morning/afternoon/evening) personalization.
     */
    public String generateDailyBriefingSentence(String interestsCsv, String keyTopicsCsv, String language, String daypart) {
        return groqBriefingService.generateDailyBriefingSentence(interestsCsv, keyTopicsCsv, language, daypart);
    }

    /**
     * Rich multi-sentence (3–4 sentences) briefing using additional recent headlines context.
     * Headlines list should be concise titles (already sanitized) in priority order.
     */
    public String generateDailyBriefingParagraph(String interestsCsv, String keyTopicsCsv, List<String> headlines, String daypart) {
        return groqBriefingService.generateDailyBriefingParagraph(interestsCsv, keyTopicsCsv, headlines, daypart);
    }

    /**
     * Multi-interest structured briefing: minimum 3 interests, each gets exactly one concise sentence (<=22 words) using 1-2 headlines.
     */
    public String generateDailyBriefingMultiInterest(Map<String, List<String>> interestHeadlines, String daypart) {
        return groqBriefingService.generateDailyBriefingMultiInterest(interestHeadlines, daypart);
    }

    /**
     * Generate structured briefing bullet points (one per supplied headline) and return as list of sentences.
     * Always requests strictly formatted JSON: { "briefing_points": ["...","..."] }
     * Falls back to trimmed headlines if API fails or response invalid.
     */
    public List<String> generateStructuredBriefingPoints(List<String> headlines) {
        return groqBriefingService.generateStructuredBriefingPoints(headlines);
    }
}