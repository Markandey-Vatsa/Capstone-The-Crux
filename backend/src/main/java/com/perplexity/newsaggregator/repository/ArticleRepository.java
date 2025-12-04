package com.perplexity.newsaggregator.repository;

import com.perplexity.newsaggregator.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.mongodb.repository.Query;

@Repository
public interface ArticleRepository extends MongoRepository<Article, String>, ArticleRepositoryCustom {
    
    
    // Enhanced search: Find articles where title, content, OR summary contains keyword
    Page<Article> findByTitleContainingIgnoreCaseOrContentContainingIgnoreCaseOrSummaryContainingIgnoreCase(
            String title, String content, String summary, Pageable pageable);
    
    // Check if article with URL already exists to prevent duplicates
    boolean existsByUrl(String url);
    
    // Find articles ordered by publish date descending
    Page<Article> findAllByOrderByPublishDateDesc(Pageable pageable);
    
    // Find articles by source ordered by publish date descending
    Page<Article> findBySourceOrderByPublishDateDesc(String source, Pageable pageable);
    

    // Find articles where any tag is in provided list ordered by publish date descending
    Page<Article> findByTagsInOrderByPublishDateDesc(java.util.List<String> tags, Pageable pageable);

    // Find articles where any domain category is in provided list ordered by publish date descending
    Page<Article> findByDomainCategoriesInOrderByPublishDateDesc(java.util.List<String> domainCategories, Pageable pageable);

    // Removed biasSignal query method (field deprecated)

    // NEW: Find articles whose tags array contains (case-insensitive) the provided tag, ordered by publish date desc
    Page<Article> findByTagsContainingIgnoreCaseOrderByPublishDateDesc(String tag, Pageable pageable);

    @Query("{ $or: [ " +
           "{ 'title': { $regex: ?0, $options: 'i' } }, " +
           "{ 'content': { $regex: ?0, $options: 'i' } }, " +
           "{ 'summary': { $regex: ?0, $options: 'i' } }, " +
           "{ 'source': { $regex: ?0, $options: 'i' } }, " +
           "{ 'tags': { $elemMatch: { $regex: ?0, $options: 'i' } } }, " +
           "{ 'domainCategories': { $elemMatch: { $regex: ?0, $options: 'i' } } } " +
           "] }")
    Page<Article> searchByKeywordRegex(String regex, Pageable pageable);
    
    // Enhanced search with scoring/relevance (searches domains first, then keyword tags, then content)
    @Query("{ $or: [ " +
           "{ 'tags': { $elemMatch: { $regex: ?0, $options: 'i' } } }, " +
           "{ 'domainCategories': { $elemMatch: { $regex: ?0, $options: 'i' } } }, " +
           "{ 'title': { $regex: ?0, $options: 'i' } }, " +
           "{ 'summary': { $regex: ?0, $options: 'i' } } " +
           "] }")
    Page<Article> searchByKeywordWithTagPriority(String regex, Pageable pageable);
    
    // Find distinct sources for efficient source counting
    @Query(value = "{}", fields = "{ 'source' : 1 }")
    java.util.List<Article> findAllSourcesOnly();
    
    // Find distinct source names (for source selection in user preferences)
    @Query(value = "{}", fields = "{ 'source' : 1 }")
    java.util.List<Article> findDistinctSourcesRaw();
    
    // Find articles by source provider (e.g., 'GNews', 'RSS', 'Guardian-API')
    Page<Article> findBySourceProviderOrderByPublishDateDesc(String sourceProvider, Pageable pageable);
}
