package com.perplexity.newsaggregator.service;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service responsible for generating daily briefings for users.
 * Handles both traditional paragraph briefings and structured bullet-point briefings.
 */
@Service
public class DailyBriefingService {
    
    private static final Logger logger = LoggerFactory.getLogger(DailyBriefingService.class);
    private static final String MORE_NEWS_MESSAGE = "More news will be updated if new news is updated.";
    
    @Autowired
    private ArticleRepository articleRepository;
    
    @Autowired
    private GroqService groqService;
    
    // Simple in-memory cache for daily briefings (username -> entry)
    private final ConcurrentHashMap<String, BriefingCacheEntry> briefingCache = new ConcurrentHashMap<>();
    private static final long BRIEFING_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30); // 30 min default
    
    /**
     * Legacy wrapper (profession not supplied) -> still attempt briefing with interests only.
     */
    public String buildDailyBriefing(List<String> interests) {
        return buildDailyBriefing(interests, null, null, null, null);
    }

    /**
     * Main daily briefing builder. The language parameter is currently unused (reserved for future i18n support)
     * and intentionally ignored to avoid removal churn.
     */
    public String buildDailyBriefing(List<String> interests, String username, String profession, String ignoredLanguage, String daypart) {
        String cacheKey = username == null ? "__anon__" : username;
        long now = System.currentTimeMillis();
        BriefingCacheEntry cached = briefingCache.get(cacheKey);
        if (cached != null && (now - cached.createdAt) < BRIEFING_TTL_MILLIS) {
            return cached.briefing;
        }

        // 1. Sanitize & limit interests (preserve original ordering = implicit user priority)
        List<String> sanitizedInterests = interests == null ? List.of() : interests.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .limit(12) // hard cap
                .collect(Collectors.toList());

        // 2. Include profession-derived categories AFTER explicit interests (lower priority)
        List<String> professionCats = profession == null ? List.of() : ProfessionCategoryMapper.map(profession).stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
        List<String> combinedOrdered = new ArrayList<>(sanitizedInterests);
        for (String pc : professionCats) {
            if (!combinedOrdered.stream().anyMatch(i -> i.equalsIgnoreCase(pc))) {
                combinedOrdered.add(pc);
            }
        }

        // 3. Per-interest query: fetch up to TWO most recent (last 24h) articles per interest (need >=3 distinct interests)
        int MAX_INTEREST_SLOTS = 6; // allow more to increase chance of >=3 valid interests
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Article> diversified = new ArrayList<>();
        HashSet<String> seenIds = new HashSet<>();
        Map<String, List<Article>> interestToArticles = new LinkedHashMap<>();
        for (String interestTag : combinedOrdered) {
            if (interestToArticles.size() >= MAX_INTEREST_SLOTS) break;
            try {
                Page<Article> page = articleRepository.findByTagsInOrderByPublishDateDesc(List.of(interestTag), PageRequest.of(0, 6));
                List<Article> hits = page.getContent().stream()
                        .filter(a -> a.getPublishDate() != null && a.getPublishDate().toInstant().isAfter(cutoff))
                        .filter(a -> a.getTags() != null && a.getTags().stream().anyMatch(t -> t.equalsIgnoreCase(interestTag)))
                        .filter(a -> a.getTitle() != null && !a.getTitle().isBlank())
                        .filter(a -> seenIds.add(a.getId()))
                        .limit(2) // at most 2 per interest
                        .collect(Collectors.toList());
                if (!hits.isEmpty()) {
                    interestToArticles.put(interestTag, hits);
                    diversified.addAll(hits);
                }
            } catch (Exception ex) {
                logger.debug("Per-interest article query failed for '{}': {}", interestTag, ex.getMessage());
            }
        }

        if (interestToArticles.size() < 3) { // not enough diversity -> fallback path
            // Fallback: pick top 3 most recent (24h) general articles
            Page<Article> recentPage = articleRepository.findAllByOrderByPublishDateDesc(PageRequest.of(0, 40));
            diversified = recentPage.getContent().stream()
                    .filter(a -> a.getPublishDate() != null && a.getPublishDate().toInstant().isAfter(cutoff))
                    .sorted((a, b) -> b.getPublishDate().compareTo(a.getPublishDate()))
                    .limit(3)
                    .collect(Collectors.toList());
            interestToArticles.clear();
            if (!diversified.isEmpty()) {
                interestToArticles.put("General", diversified);
            }
            logger.debug("Daily briefing fallback activated (insufficient diversified interests). Using {} general articles.", diversified.size());
        }

        // 4. Derive key topics from the diversified set (prefer explicit interest matches to ensure balance)
        List<String> matchedInterestTopics = diversified.stream()
            .flatMap(a -> a.getTags() == null ? java.util.stream.Stream.<String>empty() : a.getTags().stream())
            .map(String::trim)
            .filter(t -> !t.isBlank())
            .collect(Collectors.toList());
        // Order: keep original order but distinct
        List<String> keyTopics = new ArrayList<>();
        for (String t : matchedInterestTopics) {
            if (keyTopics.stream().noneMatch(kt -> kt.equalsIgnoreCase(t))) {
                keyTopics.add(capitalizeSafe(t));
            }
        }
        if (keyTopics.isEmpty()) {
            // fallback: first meaningful word from each title
            List<String> fallback = new ArrayList<>();
            for (Article a : diversified) {
                if (a.getTitle() == null) continue;
                String[] parts = a.getTitle().trim().split("\\s+");
                if (parts.length > 0) {
                    String candidate = parts[0].replaceAll("[^A-Za-z0-9-]", "");
                    if (candidate.length() > 2 && fallback.stream().noneMatch(x -> x.equalsIgnoreCase(candidate))) {
                        fallback.add(capitalizeSafe(candidate));
                    }
                }
            }
            keyTopics = fallback;
        }
        // Defensive trim
        if (keyTopics.size() > 6) keyTopics = keyTopics.subList(0, 6);

        // Build CSV strings
        String interestsCsv = String.join(", ", combinedOrdered.isEmpty() ? sanitizedInterests : combinedOrdered);
        String keyTopicsCsv = keyTopics.isEmpty() ? "top developments" : String.join(", ", keyTopics);

        // 5. Collect article titles for additional implicit context (not passed to sentence generator directly)
        List<String> titles = diversified.stream()
                .map(a -> a.getTitle() == null ? null : a.getTitle().replaceAll("\n", " ").trim())
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());
        if (logger.isDebugEnabled()) {
            logger.debug("Daily briefing diversity titles: {}", titles);
        }

        // Build multi-interest headline map (Interest -> up to 2 headlines)
        Map<String, List<String>> interestHeadlines = new LinkedHashMap<>();
        for (var e : interestToArticles.entrySet()) {
            List<String> heads = e.getValue().stream()
                    .map(a -> a.getTitle() == null ? null : a.getTitle().replaceAll("\n", " ").trim())
                    .filter(s -> s != null && !s.isBlank())
                    .limit(2)
                    .collect(Collectors.toList());
            if (!heads.isEmpty()) interestHeadlines.put(capitalizeSafe(e.getKey()), heads);
        }
        String briefing;
        if (interestHeadlines.size() >= 3) {
            briefing = groqService.generateDailyBriefingMultiInterest(interestHeadlines, daypart);
        } else {
            // fallback to paragraph (richer) then sentence if still empty
            List<String> headlineContext = diversified.stream()
                    .map(a -> a.getTitle() == null ? null : a.getTitle().replaceAll("\n"," ").trim())
                    .filter(s -> s != null && !s.isBlank())
                    .limit(6)
                    .collect(Collectors.toList());
            briefing = groqService.generateDailyBriefingParagraph(interestsCsv, keyTopicsCsv, headlineContext, daypart);
            if (briefing == null || briefing.isBlank()) {
                briefing = groqService.generateDailyBriefingSentence(interestsCsv, keyTopicsCsv, null, daypart);
            }
        }
        if (briefing == null || briefing.isBlank()) {
            briefing = "Good " + (daypart != null ? daypart : "day") + ". Pulse: " + keyTopicsCsv + ".";
        }

        briefingCache.put(cacheKey, new BriefingCacheEntry(briefing, now));
        return briefing;
    }

    private String capitalizeSafe(String in) {
        if (in == null || in.isBlank()) return in;
        return in.substring(0,1).toUpperCase() + (in.length() > 1 ? in.substring(1) : "");
    }

    private record BriefingCacheEntry(String briefing, long createdAt) {}

    // === Briefing Cache Control ===
    public void invalidateBriefingCache(String username) {
        if (username == null) return;
        briefingCache.remove(username);
        logger.debug("Briefing cache invalidated for user {}", username);
    }

    public String regenerateBriefingNow(User user) {
        if (user == null) return "";
        invalidateBriefingCache(user.getUsername());
        return buildDailyBriefing(user.getInterests(), user.getUsername(), user.getProfession(), null, null);
    }

    // ================= Structured Briefing (New) =================
    /**
     * New structured briefing returning bullet point sentences (delegates summarization to GroqService).
     * Selects balanced set of recent headlines based on user interests with fallback variety.
     */
    public List<String> buildStructuredBriefing(User user) {
        if (user == null) return List.of();

        List<String> interests = user.getInterests();
        List<String> normalizedInterests = interests == null ? List.of() : interests.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(s -> s.length() > 40 ? s.substring(0, 40) : s)
                .distinct()
                .limit(8)
                .toList();

        List<String> professionCats = user.getProfession() == null ? List.of()
                : ProfessionCategoryMapper.map(user.getProfession()).stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(6)
                    .collect(Collectors.toList());

        boolean hasInterests = !normalizedInterests.isEmpty();
        boolean hasProfession = !professionCats.isEmpty();

        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Article> matched = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        List<String> focusCategories = new ArrayList<>();
        if (hasInterests) {
            focusCategories.addAll(normalizedInterests);
        }
        if (focusCategories.size() < 8 && hasProfession) {
            professionCats.stream()
                    .filter(pc -> focusCategories.stream().noneMatch(fc -> fc.equalsIgnoreCase(pc)))
                    .forEach(focusCategories::add);
        }

        if (!focusCategories.isEmpty()) {
            for (String focus : focusCategories) {
                if (focus == null || focus.isBlank()) continue;
                try {
                    Page<Article> page = articleRepository.findByTagsInOrderByPublishDateDesc(
                            List.of(focus), PageRequest.of(0, 6));
                    List<Article> hits = page.getContent().stream()
                            .filter(a -> a.getPublishDate() != null && a.getPublishDate().toInstant().isAfter(cutoff))
                            .filter(a -> matchesInterest(a, focus))
                            .filter(a -> a.getTitle() != null && !a.getTitle().isBlank())
                            .filter(a -> seenIds.add(a.getId()))
                            .limit(2)
                            .collect(Collectors.toList());
                    matched.addAll(hits);
                } catch (Exception ex) {
                    logger.debug("Structured briefing query failed for '{}': {}", focus, ex.getMessage());
                }
            }
        }

        if (matched.size() < 3 && hasProfession) {
            for (String professionFocus : professionCats) {
                if (professionFocus == null || professionFocus.isBlank()) continue;
                try {
                    Page<Article> page = articleRepository.findByDomainCategoriesInOrderByPublishDateDesc(
                            List.of(professionFocus), PageRequest.of(0, 6));
                    List<Article> hits = page.getContent().stream()
                            .filter(a -> a.getPublishDate() != null)
                            .filter(a -> a.getPublishDate().toInstant().isAfter(cutoff.minus(48, ChronoUnit.HOURS)))
                            .filter(a -> a.getTitle() != null && !a.getTitle().isBlank())
                            .filter(a -> seenIds.add(a.getId()))
                            .limit(3)
                            .collect(Collectors.toList());
                    matched.addAll(hits);
                } catch (Exception ex) {
                    logger.debug("Extended profession fallback failed for '{}': {}", professionFocus, ex.getMessage());
                }
                if (matched.size() >= 4) {
                    break;
                }
            }
        }

        if (matched.isEmpty()) {
            if (hasInterests) {
                String focus = formatFocusList(normalizedInterests);
                return List.of(
                        "No fresh stories matched your saved interests (" + focus + ").",
                        MORE_NEWS_MESSAGE
                );
            }
            if (hasProfession) {
                String focus = formatFocusList(professionCats);
                return List.of(
                        "No new stories matched your profession focus (" + focus + ").",
                        "Add specific interests to your profile to widen future briefings."
                );
            }
        }

        if (matched.isEmpty()) {
            List<Article> recent = articleRepository.findAllByOrderByPublishDateDesc(PageRequest.of(0, 40))
                    .getContent();
            matched = recent.stream()
                    .filter(a -> a.getPublishDate() != null && a.getPublishDate().toInstant().isAfter(cutoff))
                    .filter(a -> a.getTitle() != null && !a.getTitle().isBlank())
                    .limit(5)
                    .collect(Collectors.toList());
        }

        if (matched.isEmpty()) {
            return List.of(
                    "No recent stories are available right now.",
                    MORE_NEWS_MESSAGE
            );
        }

        List<String> headlines = matched.stream()
                .map(Article::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.replaceAll("\n", " ").trim())
                .distinct()
                .limit(8)
                .collect(Collectors.toList());

        if (headlines.isEmpty()) {
            return List.of(
                    "No recent stories are available right now.",
                    MORE_NEWS_MESSAGE
            );
        }

        List<String> generated = groqService.generateStructuredBriefingPoints(headlines);
        List<String> cleanedGenerated = generated == null ? List.of() : generated.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());

        if (cleanedGenerated.size() >= 2) {
            return cleanedGenerated;
        }

        List<String> fallback = fallbackBriefingFromArticles(matched, hasInterests ? normalizedInterests : professionCats);
        if (fallback.size() < 2) {
            fallback.add(MORE_NEWS_MESSAGE);
        }
        return fallback;
    }

    private String formatFocusList(List<String> focusValues) {
        if (focusValues == null || focusValues.isEmpty()) return "";
        if (focusValues.size() <= 3) {
            return String.join(", ", focusValues);
        }
        return String.join(", ", focusValues.subList(0, 3)) + ", others";
    }

    private String simpleBriefingSentence(String headline) {
        if (headline == null || headline.isBlank()) return "Top update pending.";
        String trimmed = headline.trim();
        if (!trimmed.endsWith(".")) {
            trimmed = trimmed + ".";
        }
        return "Top update: " + trimmed;
    }

    private List<String> fallbackBriefingFromArticles(List<Article> articles, List<String> focusValues) {
        List<Article> usable = articles == null ? List.of() : articles.stream()
                .filter(a -> a != null && a.getTitle() != null && !a.getTitle().isBlank())
                .limit(5)
                .collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Article a : usable) {
            String sentence = buildArticleBullet(a);
            if (!sentence.isBlank()) bullets.add(sentence);
            if (bullets.size() >= 3) break;
        }
        if (bullets.isEmpty() && focusValues != null && !focusValues.isEmpty()) {
            bullets.add("We're monitoring your focus on " + formatFocusList(focusValues) + ".");
        }
        return bullets;
    }

    private String buildArticleBullet(Article article) {
        if (article == null) return "";
        StringBuilder sb = new StringBuilder();
        if (article.getSource() != null && !article.getSource().isBlank()) {
            sb.append(article.getSource().trim()).append(": ");
        }
        sb.append(article.getTitle().replaceAll("\\s+", " ").trim());
        String detail = article.getSummary();
        if (detail == null || detail.isBlank()) detail = article.getContent();
        if (detail != null && !detail.isBlank()) {
            String clean = detail.replaceAll("\\s+", " ").trim();
            if (clean.length() > 160) clean = clean.substring(0, 157) + "…";
            if (!clean.isBlank()) {
                sb.append(" — ").append(clean);
            }
        }
        String sentence = sb.toString().trim();
        if (!sentence.endsWith(".")) {
            sentence = sentence + ".";
        }
        return sentence;
    }

    private boolean matchesInterest(Article a, String needle) {
        if (needle == null || a == null) return false;
        String n = needle.toLowerCase();
        if (a.getTitle() != null && a.getTitle().toLowerCase().contains(n)) return true;
        if (a.getSummary() != null && a.getSummary().toLowerCase().contains(n)) return true;
        if (a.getTags() != null) {
            for (String t : a.getTags()) { if (t != null && t.toLowerCase().contains(n)) return true; }
        }
        return false;
    }
}