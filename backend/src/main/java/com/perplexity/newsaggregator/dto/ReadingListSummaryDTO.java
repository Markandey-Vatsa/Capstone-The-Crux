package com.perplexity.newsaggregator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadingListSummaryDTO {
    private String id;
    private String name;
    private String colorTheme;
    private int displayOrder;
    private int articleCount;
    private Date createdAt;
    private Date updatedAt;
    
    // Sharing fields
    private boolean isPublic;
    private String shareToken;
    private Date sharedAt;
    
    // Constructor without sharing fields (for backward compatibility)
    public ReadingListSummaryDTO(String id, String name, String colorTheme, int displayOrder, int articleCount, Date createdAt, Date updatedAt) {
        this.id = id;
        this.name = name;
        this.colorTheme = colorTheme;
        this.displayOrder = displayOrder;
        this.articleCount = articleCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isPublic = false;
        this.shareToken = null;
        this.sharedAt = null;
    }
}