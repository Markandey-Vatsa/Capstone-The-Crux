package com.perplexity.newsaggregator.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.Date;
import java.util.List;
import com.perplexity.newsaggregator.dto.StructuredBiasAnalysis;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "articles")
@CompoundIndexes({
    @CompoundIndex(name = "idx_tags_publishDate", def = "{tags: 1, publishDate: -1}" ),
    @CompoundIndex(name = "idx_domainCategories_publishDate", def = "{domainCategories: 1, publishDate: -1}" )
})
public class Article {
    @Id
    private String id;
    
    private String title;
    private String content;
    private String summary; // Short description/summary for display
    private String imageUrl; // Featured image URL from RSS feed
    
    @Indexed
    private String source;
    
    @Indexed
    private Date publishDate;
    
    @Indexed(unique = true)
    private String url;
    
    @Indexed
    private String category;

    // User-facing interest buckets (AI mapped; title case). Multikey index for fast tag queries
    @Indexed
    private List<String> tags;

    // Fine-grained contextual keywords (AI generated) retained for search & analytics
    @Indexed
    private List<String> domainCategories;

    // New: Structured bias analysis result persisted from Gemini (Option A)
    private StructuredBiasAnalysis structuredBiasAnalysis;

    // NEW FIELDS for API integration and quality control
    @Indexed
    private String originalSource;      // Original publisher: "The Hindu", "BBC News"
    
    @Indexed
    private String sourceProvider;      // How we got it: "GNews", "NewsAPI", "RSS", "Guardian-API"
    
    @Indexed
    private String language;            // Language code: "en"
    
    private Integer contentLength;      // Content length for quality filtering
    
    @Indexed
    private String contentQuality;      // "excellent" (300+), "acceptable" (200-299), "poor" (<200)
    
    private Boolean isDuplicate;        // Duplicate detection flag
    
    private String duplicateOf;         // Original article ID if this is a duplicate
    
    private Double qualityScore;        // Overall quality score 0.0 to 10.0
    
    private Date lastUpdated;           // When this article was last modified
}
