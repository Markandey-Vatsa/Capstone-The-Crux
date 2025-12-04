package com.perplexity.newsaggregator.dto;

import com.perplexity.newsaggregator.entity.Article;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicReadingListDTO {
    private String name;
    private String colorTheme;
    private String ownerUsername;
    private Date sharedAt;
    private int articleCount;
    private List<Article> articles;
    
    // Constructor without articles (for list metadata only)
    public PublicReadingListDTO(String name, String colorTheme, String ownerUsername, Date sharedAt, int articleCount) {
        this.name = name;
        this.colorTheme = colorTheme;
        this.ownerUsername = ownerUsername;
        this.sharedAt = sharedAt;
        this.articleCount = articleCount;
        this.articles = null;
    }
}