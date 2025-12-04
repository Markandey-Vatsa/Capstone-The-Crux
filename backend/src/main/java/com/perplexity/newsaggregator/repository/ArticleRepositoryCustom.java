package com.perplexity.newsaggregator.repository;

import com.perplexity.newsaggregator.service.ArticleSourceService.SourceCount;
import java.util.List;

public interface ArticleRepositoryCustom {
    List<SourceCount> getSourceCounts();
}