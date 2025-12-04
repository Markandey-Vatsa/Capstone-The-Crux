package com.perplexity.newsaggregator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central authoritative taxonomy for contextual categorization.
 * IMPORTANT: These strings (including capitalization & spacing) are the exact
 * values produced by the AI classifier (Groq) and stored in Article.tags.
 * Frontend must present the same list for user interest selection.
 */
public final class TaxonomyUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_TAXONOMY_CLASSPATH = "taxonomy/base-taxonomy.json";
    private static final String CUSTOM_TAXONOMY_PROPERTY = "taxonomy.custom.file";
    private static final String PENDING_TAXONOMY_PROPERTY = "taxonomy.pending.file";
    private static final String DEFAULT_CUSTOM_TAXONOMY = resolveBackendPath("taxonomy-custom.json");
    private static final String DEFAULT_PENDING_TAXONOMY = resolveBackendPath("taxonomy-pending.jsonl");

    private static final Map<String, List<String>> BUCKET_TO_KEYWORDS;
    private static final Map<String, String> KEYWORD_TO_BUCKET;
    private static final List<String> CATEGORIES;
    private static final List<String> FINE_GRAINED_KEYWORDS;

    private static String resolveBackendPath(String fileName) {
        if (Files.exists(Path.of("pom.xml"))) {
            return fileName;
        }
        return Path.of("backend", fileName).toString();
    }

    static {
        Map<String, List<String>> buckets = loadBaseTaxonomy();
        mergeCustomTaxonomy(buckets);
        normalizeBucketEntries(buckets);

        BUCKET_TO_KEYWORDS = Collections.unmodifiableMap(buckets);
        CATEGORIES = Collections.unmodifiableList(new ArrayList<>(buckets.keySet()));

        Map<String, String> reverse = new LinkedHashMap<>();
        buckets.forEach((bucket, keywords) -> {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) continue;
                reverse.putIfAbsent(keyword.toLowerCase(Locale.ROOT), bucket);
            }
        });
        KEYWORD_TO_BUCKET = Collections.unmodifiableMap(reverse);

        Set<String> fine = buckets.values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        FINE_GRAINED_KEYWORDS = Collections.unmodifiableList(new ArrayList<>(fine));
    }

    private TaxonomyUtil() {}

    /**
     * @return ordered list of user-facing interest buckets.
     */
    public static List<String> all() { return CATEGORIES; }

    /**
     * @return ordered list of fine-grained keywords that map back to buckets.
     */
    public static List<String> allFineGrained() { return FINE_GRAINED_KEYWORDS; }

    /**
     * Resolve the parent bucket for the supplied keyword (case-insensitive).
     */
    public static Optional<String> bucketForKeyword(String keyword) {
        if (keyword == null) return Optional.empty();
        String norm = keyword.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return Optional.empty();
        return Optional.ofNullable(KEYWORD_TO_BUCKET.get(norm));
    }
    
    /**
     * Enhanced taxonomy lookup with case-insensitive and partial matching support
     * Supports compound terms and fuzzy matching for better keyword resolution
     */
    public static Optional<String> bucketForKeywordEnhanced(String keyword) {
        if (keyword == null) return Optional.empty();
        String norm = keyword.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return Optional.empty();
        
        // First try exact match (case-insensitive)
        Optional<String> exactMatch = Optional.ofNullable(KEYWORD_TO_BUCKET.get(norm));
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        
        // Try partial matching for compound terms
        // Check if any taxonomy keyword contains the input keyword or vice versa
        for (Map.Entry<String, String> entry : KEYWORD_TO_BUCKET.entrySet()) {
            String taxonomyKeyword = entry.getKey();
            String domain = entry.getValue();
            
            // Bidirectional partial matching
            if (taxonomyKeyword.contains(norm) || norm.contains(taxonomyKeyword)) {
                // Additional validation: ensure meaningful match (not just single characters)
                if (Math.min(taxonomyKeyword.length(), norm.length()) >= 3) {
                    return Optional.of(domain);
                }
            }
            
            // Word-level matching for compound terms
            String[] inputWords = norm.split("\\s+");
            String[] taxonomyWords = taxonomyKeyword.split("\\s+");
            
            // Check if significant portion of words match
            int matchingWords = 0;
            for (String inputWord : inputWords) {
                for (String taxonomyWord : taxonomyWords) {
                    if (inputWord.equals(taxonomyWord) || 
                        (inputWord.length() >= 4 && taxonomyWord.contains(inputWord)) ||
                        (taxonomyWord.length() >= 4 && inputWord.contains(taxonomyWord))) {
                        matchingWords++;
                        break;
                    }
                }
            }
            
            // If majority of words match, consider it a match
            if (matchingWords > 0 && matchingWords >= Math.min(inputWords.length, taxonomyWords.length) * 0.6) {
                return Optional.of(domain);
            }
        }
        
        return Optional.empty();
    }

    /**
     * Map a collection of fine-grained keywords to their unique parent buckets.
     */
    public static List<String> mapKeywordsToBuckets(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return keywords.stream()
                .filter(Objects::nonNull)
                .map(keyword -> bucketForKeyword(keyword).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(java.util.LinkedHashSet::new), ArrayList::new));
    }

    /**
     * Retrieve the fine-grained keywords registered for a specific bucket.
     */
    public static List<String> keywordsForBucket(String bucket) {
        if (bucket == null) return List.of();
        return BUCKET_TO_KEYWORDS.getOrDefault(bucket, List.of());
    }

    /**
     * Simple heuristic bucket guess based on free-form text when AI tags fail.
     */
    public static Optional<String> guessBucketFromText(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : FINE_GRAINED_KEYWORDS) {
            if (keyword.length() < 3) continue;
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                Optional<String> bucket = bucketForKeyword(keyword);
                if (bucket.isPresent()) {
                    return bucket;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return unmodifiable map of buckets to keywords (intended for read-only diagnostics).
     */
    public static Map<String, List<String>> bucketKeywordMap() { return BUCKET_TO_KEYWORDS; }

    /**
     * IMPROVED SYSTEM: Map specific tags to domains with learning capability
     * This is the fallback method when Groq API is unavailable
     */
    public static List<String> mapTagsToDomainsWithLearning(List<String> specificTags) {
        if (specificTags == null || specificTags.isEmpty()) return List.of();
        
        Set<String> domains = new LinkedHashSet<>();
        for (String tag : specificTags) {
            // First try enhanced lookup with partial matching
            Optional<String> domain = bucketForKeywordEnhanced(tag);
            if (domain.isPresent()) {
                domains.add(domain.get());
            } else {
                // Try to guess domain from text similarity
                Optional<String> guessed = guessBucketFromText(tag);
                if (guessed.isPresent()) {
                    domains.add(guessed.get());
                    // Auto-learn this mapping for future use
                    learnTagMapping(tag, guessed.get());
                }
            }
        }
        
        return new ArrayList<>(domains);
    }

    /**
     * IMPROVED SYSTEM: Learn new tag mappings to domains with smart validation
     * This method adds new tags to the custom taxonomy file for future use
     */
    public static void learnTagMappings(List<String> specificTags, List<String> domains) {
        if (specificTags == null || specificTags.isEmpty() || domains == null || domains.isEmpty()) {
            return;
        }
        
        // For each tag, if it's not already in taxonomy, learn it
        for (String tag : specificTags) {
            if (tag == null || tag.trim().isEmpty()) continue;
            
            String cleanTag = validateAndCleanTag(tag.trim());
            if (cleanTag == null) continue; // Skip invalid tags
            
            // Use enhanced lookup to check if tag already exists
            Optional<String> existingDomain = bucketForKeywordEnhanced(cleanTag);
            if (existingDomain.isEmpty()) {
                // Tag is new, learn it by mapping to the most relevant domain
                String targetDomain = findBestDomainMatch(cleanTag, domains);
                learnTagMapping(cleanTag, targetDomain);
            }
        }
    }
    
    /**
     * Validate and clean a tag before learning
     * Returns null if tag should be skipped
     */
    private static String validateAndCleanTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) return null;
        
        String cleaned = tag.trim();
        
        // Skip generic/useless tags
        Set<String> skipWords = Set.of(
            "news", "report", "update", "article", "story", "breaking", 
            "latest", "new", "recent", "today", "yesterday", "tomorrow",
            "analysis", "opinion", "editorial", "commentary"
        );
        
        if (skipWords.contains(cleaned.toLowerCase())) {
            return null;
        }
        
        // Skip very short tags (less than 3 characters)
        if (cleaned.length() < 3) return null;
        
        // Skip tags that are just numbers
        if (cleaned.matches("^\\d+$")) return null;
        
        // Ensure proper capitalization (Title Case)
        if (cleaned.equals(cleaned.toLowerCase()) && !cleaned.contains(" ")) {
            // Single word - capitalize first letter
            cleaned = cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1).toLowerCase();
        } else if (cleaned.contains(" ")) {
            // Multi-word - title case
            String[] words = cleaned.split("\\s+");
            StringBuilder titleCase = new StringBuilder();
            for (String word : words) {
                if (word.length() > 0) {
                    if (titleCase.length() > 0) titleCase.append(" ");
                    titleCase.append(word.substring(0, 1).toUpperCase())
                             .append(word.substring(1).toLowerCase());
                }
            }
            cleaned = titleCase.toString();
        }
        
        return cleaned;
    }
    
    /**
     * Find the best domain match for a tag from available domains
     */
    private static String findBestDomainMatch(String tag, List<String> domains) {
        if (domains.size() == 1) return domains.get(0);
        
        String tagLower = tag.toLowerCase();
        
        // Check for obvious keyword matches in domain names
        for (String domain : domains) {
            String domainLower = domain.toLowerCase();
            if (tagLower.contains(domainLower.split(" ")[0]) || 
                domainLower.contains(tagLower.split(" ")[0])) {
                return domain;
            }
        }
        
        // Return first domain as fallback
        return domains.get(0);
    }

    /**
     * IMPROVED SYSTEM: Learn a single tag mapping to a domain with confidence tracking
     * Adds the tag to the custom taxonomy file under the specified domain
     */
    private static void learnTagMapping(String tag, String domain) {
        if (tag == null || tag.trim().isEmpty() || domain == null || domain.trim().isEmpty()) {
            return;
        }
        
        try {
            Path customPath = Path.of(System.getProperty(CUSTOM_TAXONOMY_PROPERTY, DEFAULT_CUSTOM_TAXONOMY));
            
            Map<String, Object> customTaxonomy;
            if (Files.exists(customPath)) {
                // Load existing custom taxonomy
                customTaxonomy = MAPPER.readValue(customPath.toFile(), new TypeReference<Map<String, Object>>() {});
            } else {
                // Create new custom taxonomy with metadata
                customTaxonomy = new LinkedHashMap<>();
                customTaxonomy.put("_metadata", Map.of(
                    "created", java.time.Instant.now().toString(),
                    "version", "2.0",
                    "description", "Auto-learned tag mappings with confidence tracking"
                ));
            }
            
            // Ensure domain exists
            if (!customTaxonomy.containsKey(domain)) {
                customTaxonomy.put(domain, new ArrayList<String>());
            }
            
            // Get existing tags for domain
            @SuppressWarnings("unchecked")
            List<String> domainTags = (List<String>) customTaxonomy.get(domain);
            if (domainTags == null) {
                domainTags = new ArrayList<>();
                customTaxonomy.put(domain, domainTags);
            }
            
            // Add tag if not already present
            String trimmedTag = tag.trim();
            if (!domainTags.contains(trimmedTag)) {
                domainTags.add(trimmedTag);
                
                // Sort tags alphabetically for better organization
                domainTags.sort(String.CASE_INSENSITIVE_ORDER);
                
                // Write back to file
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(customPath.toFile(), customTaxonomy);
                
                // Enhanced logging with timestamp
                String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                System.out.println("🧠 [" + timestamp + "] LEARNED: '" + trimmedTag + "' → '" + domain + "' (Total: " + domainTags.size() + " tags)");
            }
            
        } catch (IOException e) {
            // Don't break the application if learning fails
            System.err.println("Failed to learn tag mapping: " + tag + " → " + domain + " (" + e.getMessage() + ")");
        }
    }
    
    /**
     * Add unmapped keyword to pending taxonomy file with duplicate prevention
     * Maintains existing JSONL format for backward compatibility
     */
    public static void addToPendingTaxonomy(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        
        String cleanKeyword = validateAndCleanTag(keyword.trim());
        if (cleanKeyword == null) {
            return; // Skip invalid keywords
        }
        
        try {
            Path pendingPath = Path.of(System.getProperty(PENDING_TAXONOMY_PROPERTY, DEFAULT_PENDING_TAXONOMY));
            
            // Check if keyword already exists in pending taxonomy
            if (isPendingKeywordExists(cleanKeyword, pendingPath)) {
                return; // Skip duplicate
            }
            
            // Create parent directories if they don't exist
            if (pendingPath.getParent() != null) {
                Files.createDirectories(pendingPath.getParent());
            }
            
            // Append new keyword to JSONL file
            String jsonLine = "{\"keyword\": \"" + cleanKeyword.replace("\"", "\\\"") + "\"}\n";
            Files.write(pendingPath, jsonLine.getBytes(), 
                Files.exists(pendingPath) ? 
                    java.nio.file.StandardOpenOption.APPEND : 
                    java.nio.file.StandardOpenOption.CREATE);
            
            // Enhanced logging with timestamp
            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            System.out.println("📝 [" + timestamp + "] PENDING: '" + cleanKeyword + "' added for manual review");
            
        } catch (IOException e) {
            System.err.println("Failed to add keyword to pending taxonomy: " + cleanKeyword + " (" + e.getMessage() + ")");
        }
    }
    
    /**
     * Check if a keyword already exists in the pending taxonomy file
     */
    private static boolean isPendingKeywordExists(String keyword, Path pendingPath) {
        if (!Files.exists(pendingPath)) {
            return false;
        }
        
        try {
            List<String> lines = Files.readAllLines(pendingPath);
            String targetKeyword = keyword.toLowerCase().trim();
            
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    // Parse JSONL format: {"keyword": "value"}
                    Map<String, Object> entry = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                    Object keywordObj = entry.get("keyword");
                    if (keywordObj instanceof String existingKeyword) {
                        if (existingKeyword.toLowerCase().trim().equals(targetKeyword)) {
                            return true; // Duplicate found
                        }
                    }
                } catch (IOException e) {
                    // Skip malformed lines
                    continue;
                }
            }
        } catch (IOException e) {
            // If we can't read the file, assume keyword doesn't exist
            return false;
        }
        
        return false;
    }
    
    /**
     * Process keywords and add unmapped ones to pending taxonomy
     * This method should be called when keywords cannot be mapped to domains
     */
    public static List<String> processKeywordsWithPendingTaxonomy(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        
        List<String> mappedDomains = new ArrayList<>();
        List<String> unmappedKeywords = new ArrayList<>();
        
        for (String keyword : keywords) {
            if (keyword == null || keyword.trim().isEmpty()) continue;
            
            // Try to map keyword to existing domain
            Optional<String> domain = bucketForKeywordEnhanced(keyword);
            if (domain.isPresent()) {
                mappedDomains.add(domain.get());
            } else {
                // Keyword cannot be mapped - add to pending taxonomy
                unmappedKeywords.add(keyword);
                addToPendingTaxonomy(keyword);
            }
        }
        
        // Log summary
        if (!unmappedKeywords.isEmpty()) {
            System.out.println("📊 TAXONOMY PROCESSING: " + keywords.size() + " keywords → " + 
                mappedDomains.size() + " mapped, " + unmappedKeywords.size() + " pending review");
        }
        
        return mappedDomains.stream().distinct().collect(Collectors.toList());
    }

    private static Map<String, List<String>> loadBaseTaxonomy() {
        try (InputStream is = TaxonomyUtil.class.getClassLoader().getResourceAsStream(BASE_TAXONOMY_CLASSPATH)) {
            if (is == null) {
                throw new IllegalStateException("Base taxonomy resource missing: " + BASE_TAXONOMY_CLASSPATH);
            }
            Map<String, List<String>> map = MAPPER.readValue(is, new TypeReference<Map<String, List<String>>>() {});
            return new LinkedHashMap<>(map);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load base taxonomy", e);
        }
    }

    private static void mergeCustomTaxonomy(Map<String, List<String>> base) {
        Path customPath = Path.of(System.getProperty(CUSTOM_TAXONOMY_PROPERTY, DEFAULT_CUSTOM_TAXONOMY));
        if (!Files.exists(customPath)) {
            return;
        }
        try {
            Map<String, Object> custom = MAPPER.readValue(customPath.toFile(), new TypeReference<Map<String, Object>>() {});
            custom.forEach((bucket, value) -> {
                // Skip metadata entries
                if (bucket == null || bucket.isBlank() || bucket.startsWith("_") || value == null) {
                    return;
                }
                // Only process entries that are lists of strings
                if (value instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<String> keywords = (List<String>) list;
                    if (!keywords.isEmpty()) {
                        base.computeIfAbsent(bucket, k -> new ArrayList<>()).addAll(keywords);
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load custom taxonomy from " + customPath, e);
        }
    }

    private static void normalizeBucketEntries(Map<String, List<String>> buckets) {
        for (Map.Entry<String, List<String>> entry : buckets.entrySet()) {
            List<String> keywords = entry.getValue();
            if (keywords == null) {
                entry.setValue(List.of());
            } else {
                LinkedHashSet<String> deduped = keywords.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                entry.setValue(List.copyOf(deduped));
            }
        }
    }
}
