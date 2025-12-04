package com.perplexity.newsaggregator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateReadingListRequest {
    @Size(max = 100, message = "Reading list name cannot exceed 100 characters")
    private String name;
    
    private String colorTheme;
}