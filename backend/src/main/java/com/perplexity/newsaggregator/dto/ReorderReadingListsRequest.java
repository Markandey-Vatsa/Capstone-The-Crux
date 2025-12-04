package com.perplexity.newsaggregator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReorderReadingListsRequest {
    @NotNull(message = "List order cannot be null")
    private List<String> orderedListIds;
}