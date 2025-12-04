package com.perplexity.newsaggregator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GNewsApiResponse {
    private int totalArticles;
    private List<GNewsArticle> articles;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GNewsArticle {
        private String id;
        private String title;
        private String description;
        private String content;
        private String url;
        private String image;
        private String publishedAt;
        private String lang;
        private GNewsSource source;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class GNewsSource {
            private String id;
            private String name;
            private String url;
            private String country;
        }
    }
}