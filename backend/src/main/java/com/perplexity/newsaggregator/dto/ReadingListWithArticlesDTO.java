package com.perplexity.newsaggregator.dto;

import com.perplexity.newsaggregator.entity.Article;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadingListWithArticlesDTO {
    private String id;
    private String name;
    private String colorTheme;
    private List<Article> articles;
}