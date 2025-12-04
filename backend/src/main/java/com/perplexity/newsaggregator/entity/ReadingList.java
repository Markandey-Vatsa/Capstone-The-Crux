package com.perplexity.newsaggregator.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reading_lists")
public class ReadingList {
    @Id
    private String id;
    
    @Indexed
    private String userId;
    
    private String name;
    private String colorTheme;
    private int displayOrder;
    private Date createdAt;
    private Date updatedAt;
    
    @Indexed
    private List<String> articleIds = new ArrayList<>();
    
    // Sharing fields (with safe defaults for backward compatibility)
    @Indexed
    private boolean isPublic = false;
    
    @Indexed
    private String shareToken;
    
    private Date sharedAt;
    
    // Constructor for creating new reading lists
    public ReadingList(String userId, String name, String colorTheme, int displayOrder) {
        this.userId = userId;
        this.name = name;
        this.colorTheme = colorTheme;
        this.displayOrder = displayOrder;
        this.createdAt = new Date();
        this.updatedAt = new Date();
        this.articleIds = new ArrayList<>();
        this.isPublic = false; // Default to private
        this.shareToken = null; // No token initially
        this.sharedAt = null;   // Not shared initially
    }
    
    // Helper method to update the updatedAt timestamp
    public void touch() {
        this.updatedAt = new Date();
    }
    
    // Helper methods for sharing functionality
    public void makePublic(String shareToken) {
        this.isPublic = true;
        this.shareToken = shareToken;
        this.sharedAt = new Date();
        this.touch();
    }
    
    public void makePrivate() {
        this.isPublic = false;
        // Keep shareToken for potential future sharing
        this.touch();
    }
    
    public boolean isShared() {
        return this.isPublic && this.shareToken != null;
    }
}