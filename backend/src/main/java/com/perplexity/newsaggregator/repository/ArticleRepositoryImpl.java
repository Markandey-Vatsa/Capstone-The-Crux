package com.perplexity.newsaggregator.repository;

import com.perplexity.newsaggregator.service.ArticleSourceService.SourceCount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ArticleRepositoryImpl implements ArticleRepositoryCustom {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public List<SourceCount> getSourceCounts() {
        try {
            // REVERTED: Back to original simple approach - just group by source name
            GroupOperation groupBySource = Aggregation.group("source")
                    .count().as("articleCount")
                    .first("source").as("sourceName");
            
            SortOperation sortByCount = Aggregation.sort(Sort.Direction.DESC, "articleCount");
            
            Aggregation aggregation = Aggregation.newAggregation(
                    groupBySource,
                    sortByCount
            );
            
            long startTime = System.currentTimeMillis();
            AggregationResults<SourceCount> results = mongoTemplate.aggregate(
                    aggregation, "articles", SourceCount.class);
            long duration = System.currentTimeMillis() - startTime;
            
            List<SourceCount> mappedResults = results.getMappedResults();
            
            // Log performance metrics
            System.out.println(String.format("Source aggregation completed in %dms, found %d sources", 
                duration, mappedResults.size()));
            
            return mappedResults;
            
        } catch (Exception e) {
            System.err.println("Error in source aggregation: " + e.getMessage());
            throw new RuntimeException("Failed to aggregate source counts", e);
        }
    }

}