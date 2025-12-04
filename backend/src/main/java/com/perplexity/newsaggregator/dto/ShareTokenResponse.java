package com.perplexity.newsaggregator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareTokenResponse {
    private String shareToken;
    private String shareUrl;
    private boolean isPublic;
    private Date sharedAt;
    
    // Helper method to generate full share URL
    public static ShareTokenResponse create(String shareToken, boolean isPublic, Date sharedAt, String baseUrl) {
        ShareTokenResponse response = new ShareTokenResponse();
        response.setShareToken(shareToken);
        response.setPublic(isPublic);
        response.setSharedAt(sharedAt);
        
        if (shareToken != null && isPublic) {
            response.setShareUrl(baseUrl + "/shared-reading-list.html?token=" + shareToken);
        } else {
            response.setShareUrl(null);
        }
        
        return response;
    }
    
    // Constructor for public lists
    public ShareTokenResponse(String shareToken, boolean isPublic, Date sharedAt) {
        this.shareToken = shareToken;
        this.isPublic = isPublic;
        this.sharedAt = sharedAt;
        this.shareUrl = null; // Will be set by create method
    }
}