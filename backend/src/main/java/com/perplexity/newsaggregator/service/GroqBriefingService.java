package com.perplexity.newsaggregator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for generating daily briefings and structured content using Groq API.
 * Handles briefing generation, structured points, and multi-interest briefings.
 */
@Service
public class GroqBriefingService {
    private static final Logger log = LoggerFactory.getLogger(GroqBriefingService.class);

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.1-8b-instant";

    @Value("${groq.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @EventListener(ContextRefreshedEvent.class)
    void logKeyStatus() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GroqBriefingService initialized WITHOUT API key (groq.api.key blank)");
        } else {
            // Mask all but first 6 and last 4 chars
            String masked = apiKey.length() <= 12 ? "********" : apiKey.substring(0,6) + "********" + apiKey.substring(apiKey.length()-4);
            log.info("GroqBriefingService initialized with API key present (masked={} length={})", masked, apiKey.length());
        }
    }

    /**
     * Single-shot generic chat invocation (used by dynamic bias analysis meta-prompt).
     * Returns raw model message content. Throws exception on any non-success so caller can trigger fallback.
     */
    public String singleShotChat(String prompt, int maxTokens, double temperature) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Groq API key missing");
        var root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.set("messages", mapper.createArrayNode().add(
                mapper.createObjectNode().put("role", "user").put("content", prompt)
        ));
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);
        String bodyJson = mapper.writeValueAsString(root);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());
        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
        ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Groq meta non-success status=" + response.getStatusCode());
        }
        JsonNode rootNode = mapper.readTree(response.getBody());
        String raw = rootNode.path("choices").get(0).path("message").path("content").asText("").trim();
        if (raw.isEmpty()) throw new IllegalStateException("Groq meta empty content");
        return raw;
    }

    /**
     * Generate a one-sentence analyst style daily briefing given user interests and key topics.
     */
    public String generateDailyBriefingSentence(String interestsCsv, String keyTopicsCsv) {
        return generateDailyBriefingSentence(interestsCsv, keyTopicsCsv, null, null);
    }

    /**
     * Extended variant adding optional language (ISO 639-1) and daypart (morning/afternoon/evening) personalization.
     */
    public String generateDailyBriefingSentence(String interestsCsv, String keyTopicsCsv, String language, String daypart) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Groq API key missing; returning fallback daily briefing.");
            return fallbackBriefing(interestsCsv, keyTopicsCsv, language, daypart);
        }
        String interestsPart = (interestsCsv == null || interestsCsv.isBlank()) ? "general news" : interestsCsv;
        String topicsPart = (keyTopicsCsv == null || keyTopicsCsv.isBlank()) ? "top stories" : keyTopicsCsv;
        StringBuilder prompt = new StringBuilder();
        // New enriched analyst persona prompt (Crux) requiring 2-3 sentences and time-of-day greeting
        prompt.append("You are an AI news analyst named 'Crux'. A user is interested in ")
              .append("[").append(interestsPart).append("]")
              .append(". The key topics for them today are ")
              .append("[").append(topicsPart).append("]")
              .append(". Write a friendly and insightful 2-3 sentence greeting that summarizes these key developments for their daily briefing. ")
              .append("Start with 'Good ")
              .append(daypart != null && !daypart.isBlank() ? daypart : "day")
              .append("...'. Avoid hype; be concise, actionable, and neutral. If topics are sparse, encourage the user they'll see more as the day unfolds.");
        if (language != null && !language.isBlank() && !language.equalsIgnoreCase("en")) {
            prompt.append(" Respond in ").append(languageName(language)).append(".");
        }
        try {
            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                mapper.createObjectNode().put("role", "user").put("content", prompt.toString())
            ));
            root.put("max_tokens", 180); // allow 2-3 sentences
            root.put("temperature", 0.5);
            String bodyJson = mapper.writeValueAsString(root);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Daily briefing request non-success status={} body={} ", response.getStatusCode(), response.getBody());
                return fallbackBriefing(interestsPart, topicsPart, language, daypart);
            }
            JsonNode rootNode = mapper.readTree(response.getBody());
            String content = rootNode.path("choices").get(0).path("message").path("content").asText("").trim();
            if (content.isEmpty()) return fallbackBriefing(interestsPart, topicsPart, language, daypart);
            // Ensure single sentence (truncate after first period if many)
            int period = content.indexOf('.');
            if (period > 0 && period < content.length() - 1) {
                // If there are multiple sentences, keep first complete sentence
                String firstSentence = content.substring(0, period + 1).trim();
                if (firstSentence.length() >= 40) return firstSentence; // reasonable length
            }
            return content;
        } catch (Exception e) {
            log.error("Daily briefing generation failed: {}", e.getMessage());
            return fallbackBriefing(interestsCsv, keyTopicsCsv, language, daypart);
        }
    }

    private String fallbackBriefing(String interestsCsv, String keyTopicsCsv, String language, String daypart) {
        String interestsPart = (interestsCsv == null || interestsCsv.isBlank()) ? "today's top stories" : interestsCsv;
        String topicsPart = (keyTopicsCsv == null || keyTopicsCsv.isBlank()) ? "key developments" : keyTopicsCsv;
        String greeting;
        if (daypart != null) {
            switch (daypart.toLowerCase()) {
                case "morning": greeting = "Good morning"; break;
                case "afternoon": greeting = "Good afternoon"; break;
                case "evening": greeting = "Good evening"; break;
                default: greeting = "Hello"; break;
            }
        } else greeting = "Hello";
        return greeting + "! Here's a quick pulse across " + interestsPart + " – watch: " + topicsPart + ".";
    }

    /**
     * Rich multi-sentence (3–4 sentences) briefing using additional recent headlines context.
     * Headlines list should be concise titles (already sanitized) in priority order.
     */
    public String generateDailyBriefingParagraph(String interestsCsv, String keyTopicsCsv, List<String> headlines, String daypart) {
        if (apiKey == null || apiKey.isBlank()) {
            return richFallback(interestsCsv, keyTopicsCsv, headlines, daypart);
        }
        String greeting = switch (daypart == null ? "" : daypart.toLowerCase()) {
            case "morning" -> "Good morning";
            case "afternoon" -> "Good afternoon";
            case "evening" -> "Good evening";
            default -> "Good day";
        };
        String interestsPart = (interestsCsv == null || interestsCsv.isBlank()) ? "general news" : interestsCsv;
        String topicsPart = (keyTopicsCsv == null || keyTopicsCsv.isBlank()) ? "global developments" : keyTopicsCsv;
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are 'Crux', an expert AI news analyst. A user cares about [")
                .append(interestsPart).append("] and today's key topics are [")
                .append(topicsPart).append("]. Below are recent relevant headlines.\n\n");
        int idx = 1;
        for (String h : headlines == null ? List.<String>of() : headlines) {
            if (h == null || h.isBlank()) continue;
            prompt.append(idx++).append('.').append(' ').append(h.trim()).append('\n');
            if (idx > 8) break; // cap context
        }
        prompt.append("\nTask: Write a concise, neutral, professional briefing of EXACTLY 3 to 4 sentences starting with '")
                .append(greeting).append("'. Cover: (1) macro/market or geopolitical frame if relevant, (2) the most material developments, (3) emerging risk or opportunity, (4) optional forward-looking watch.\nConstraints: 3-4 sentences only, no lists, no fluff, each sentence < 30 words, no repetition of headline wording verbatim, no apologetic language.");
        try {
            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                    mapper.createObjectNode().put("role", "user").put("content", prompt.toString())
            ));
            root.put("max_tokens", 260);
            root.put("temperature", 0.4);
            String bodyJson = mapper.writeValueAsString(root);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return richFallback(interestsCsv, keyTopicsCsv, headlines, daypart);
            }
            JsonNode rootNode = mapper.readTree(response.getBody());
            String content = rootNode.path("choices").get(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return richFallback(interestsCsv, keyTopicsCsv, headlines, daypart);
            // Ensure 3-4 sentences by truncating after 4 sentences max
            String[] sentences = content.split("(?<=[.!?])\\s+");
            if (sentences.length > 4) {
                content = java.util.Arrays.stream(sentences).limit(4).collect(java.util.stream.Collectors.joining(" "));
            }
            return content;
        } catch (Exception e) {
            log.error("Rich daily briefing generation failed: {}", e.getMessage());
            return richFallback(interestsCsv, keyTopicsCsv, headlines, daypart);
        }
    }

    /**
     * Multi-interest structured briefing: minimum 3 interests, each gets exactly one concise sentence (<=22 words) using 1-2 headlines.
     */
    public String generateDailyBriefingMultiInterest(Map<String, List<String>> interestHeadlines, String daypart) {
        String greeting = switch (daypart == null ? "" : daypart.toLowerCase()) {
            case "morning" -> "Good morning";
            case "afternoon" -> "Good afternoon";
            case "evening" -> "Good evening";
            default -> "Good day";
        };
        if (interestHeadlines == null || interestHeadlines.isEmpty()) {
            return greeting + ". No personalized signals yet – more stories will populate soon.";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return multiInterestFallback(greeting, interestHeadlines);
        }
        try {
            StringBuilder context = new StringBuilder();
            context.append("You are 'Crux', an objective AI news analyst. For EACH interest below, craft EXACTLY ONE concise sentence (<=22 words) summarizing the most material development, using ONLY the provided headlines. Output: start with '" + greeting + ",', then one sentence per interest separated by a single space. No numbering, no bullets, no labels like 'Technology:'. Avoid repeating exact headline phrasing, avoid hype, keep neutral. If a headline set is too vague, produce a cautiously generalized sentence.\n\n");
            context.append("Interests & Headlines:\n");
            interestHeadlines.forEach((k,v)-> {
                context.append("- ").append(k).append(": ");
                for (int i=0;i<v.size();i++) {
                    if (i>0) context.append(" | ");
                    context.append(v.get(i));
                }
                context.append('\n');
            });
            context.append("\nConstraints: <=6 total sentences. Do not merge interests. Do not add closing pleasantries.\n");
            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                mapper.createObjectNode().put("role", "user").put("content", context.toString())
            ));
            root.put("max_tokens", 380);
            root.put("temperature", 0.45);
            String bodyJson = mapper.writeValueAsString(root);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return multiInterestFallback(greeting, interestHeadlines);
            }
            JsonNode rootNode = mapper.readTree(response.getBody());
            String content = rootNode.path("choices").get(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return multiInterestFallback(greeting, interestHeadlines);
            return content;
        } catch (Exception e) {
            log.error("Multi-interest briefing failed: {}", e.getMessage());
            return multiInterestFallback(greeting, interestHeadlines);
        }
    }

    /**
     * Generate structured briefing bullet points (one per supplied headline) and return as list of sentences.
     * Always requests strictly formatted JSON: { "briefing_points": ["...","..."] }
     * Falls back to trimmed headlines if API fails or response invalid.
     */
    public List<String> generateStructuredBriefingPoints(List<String> headlines) {
        if (headlines == null) headlines = List.of();
        List<String> sanitized = new ArrayList<>();
        for (String h : headlines) {
            if (h == null) continue;
            String t = h.trim();
            if (!t.isBlank()) sanitized.add(t);
            if (sanitized.size() >= 8) break; // cap context to 8
        }
        if (sanitized.isEmpty()) return List.of();
        if (apiKey == null || apiKey.isBlank()) {
            // No API key -> simple fallback: return first 3 headlines truncated
            return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
        }
        try {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert news editor named 'Crux'. Produce a polished daily briefing from the headlines. For EACH headline, write ONE complete, neutral, news-style sentence (18-30 words) that:\n")
            .append("- Captures the core development (who/what + key action/outcome)\n")
            .append("- Adds brief significance or context (why it matters or next step)\n")
            .append("- Avoids repeating headline wording verbatim\n")
            .append("- No editorial hype, no emojis, no lists inside a sentence\n\n")
            .append("Return ONLY strict minified JSON: {\"briefing_points\":[\"sentence1\",\"sentence2\"]}. No commentary, no markdown, no prefix/suffix. Headlines:\n");
            int idx=1; for (String h : sanitized) { prompt.append(idx++).append('.').append(' ').append(h).append('\n'); }
            prompt.append("Format reminder: strictly {\"briefing_points\":[...]}. Do not include markdown or labels.");

            var root = mapper.createObjectNode();
            root.put("model", MODEL);
            root.set("messages", mapper.createArrayNode().add(
                    mapper.createObjectNode().put("role", "user").put("content", prompt.toString())
            ));
            root.put("max_tokens", 300);
            root.put("temperature", 0.4);
            String bodyJson = mapper.writeValueAsString(root);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
            }
            JsonNode rootNode = mapper.readTree(response.getBody());
            String content = rootNode.path("choices").get(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
            // Extract JSON object substring
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
            String jsonObj = content.substring(start, end + 1);
            try {
                JsonNode parsed = mapper.readTree(jsonObj);
                JsonNode arr = parsed.path("briefing_points");
                if (arr.isArray() && arr.size() > 0) {
                    List<String> points = new ArrayList<>();
                    arr.forEach(n -> { String s = n.asText("").trim(); if(!s.isBlank()) points.add(s); });
                    if (!points.isEmpty()) return points;
                }
            } catch (Exception parseFail) {
                log.debug("Failed to parse structured briefing JSON: {}", parseFail.getMessage());
            }
            return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
        } catch (Exception e) {
            log.error("Structured briefing generation failed: {}", e.getMessage());
            return sanitized.stream().limit(5).map(this::truncateHeadline).toList();
        }
    }

    private String truncateHeadline(String h) {
        return h.length() > 110 ? h.substring(0,107) + "…" : h;
    }

    private String multiInterestFallback(String greeting, Map<String, List<String>> interestHeadlines) {
        StringBuilder sb = new StringBuilder();
        sb.append(greeting).append(", quick pulse: ");
        boolean first = true;
        for (var entry : interestHeadlines.entrySet()) {
            var heads = entry.getValue();
            if (heads == null || heads.isEmpty()) continue;
            if (!first) sb.append(' ');
            first = false;
            sb.append(entry.getKey()).append(' ').append('-').append(' ');
            sb.append(heads.get(0));
            if (heads.size() > 1) sb.append("; ").append(heads.get(1));
            sb.append('.');
        }
        return sb.toString();
    }

    private String richFallback(String interestsCsv, String keyTopicsCsv, List<String> headlines, String daypart) {
        String greeting = switch (daypart == null ? "" : daypart.toLowerCase()) {
            case "morning" -> "Good morning";
            case "afternoon" -> "Good afternoon";
            case "evening" -> "Good evening";
            default -> "Good day";
        };
        String topics = keyTopicsCsv == null || keyTopicsCsv.isBlank() ? "today's developments" : keyTopicsCsv;
        String head1 = (headlines != null && !headlines.isEmpty()) ? headlines.get(0) : null;
        String head2 = (headlines != null && headlines.size() > 1) ? headlines.get(1) : null;
        StringBuilder sb = new StringBuilder();
        sb.append(greeting).append(". Quick pulse: ").append(topics).append('.');
        if (head1 != null) sb.append(' ').append(head1);
        if (head2 != null) sb.append(' ').append(head2);
        sb.append(' ').append("More updates expected through the day.");
        return sb.toString();
    }

    private String languageName(String code) {
        return switch (code.toLowerCase()) {
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "hi" -> "Hindi";
            case "zh" -> "Chinese";
            case "ja" -> "Japanese";
            default -> "English"; // fallback
        };
    }
}