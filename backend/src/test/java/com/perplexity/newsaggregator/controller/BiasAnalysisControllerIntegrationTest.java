package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.service.GeminiService;
import com.perplexity.newsaggregator.service.NewsService;
import com.perplexity.newsaggregator.service.ArticleSourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for BiasAnalysisController avoiding Spring context & Mockito.
 * Focus: wiring logic (404 path) and health check positive path.
 */
class BiasAnalysisControllerIntegrationTest {

    // Minimal in-memory stub for ArticleRepository
    static class StubArticleRepo implements ArticleRepository {
        @Override public Optional<Article> findById(String id) { return Optional.empty(); }
        // --- Methods required by interface hierarchy (no-op / empty) ---
        @Override public <S extends Article> S save(S entity) { return entity; }
        @Override public <S extends Article> List<S> saveAll(Iterable<S> entities) { List<S> list = new ArrayList<>(); entities.forEach(list::add); return list; }
        @Override public boolean existsById(String s) { return false; }
        @Override public List<Article> findAll() { return List.of(); }
        @Override public List<Article> findAllById(Iterable<String> strings) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(String s) { }
        @Override public void delete(Article entity) { }
        @Override public void deleteAllById(Iterable<? extends String> strings) { }
        @Override public void deleteAll(Iterable<? extends Article> entities) { }
        @Override public void deleteAll() { }
        // Custom methods (unused in this test)
        @Override public boolean existsByUrl(String url) { return false; }
        @Override public Page<Article> findAllByOrderByPublishDateDesc(Pageable pageable) { return Page.empty(); }
        @Override public Page<Article> findBySourceOrderByPublishDateDesc(String source, Pageable pageable) { return Page.empty(); }
        @Override public Page<Article> findByTagsInOrderByPublishDateDesc(List<String> tags, Pageable pageable) { return Page.empty(); }
        @Override public Page<Article> findByDomainCategoriesInOrderByPublishDateDesc(List<String> domainCategories, Pageable pageable) { return Page.empty(); }
        @Override public Page<Article> findByTitleContainingIgnoreCaseOrContentContainingIgnoreCaseOrSummaryContainingIgnoreCase(String t,String c,String s, Pageable p){ return Page.empty(); }
        @Override public Page<Article> findByTagsContainingIgnoreCaseOrderByPublishDateDesc(String tag, Pageable pageable) { return Page.empty(); }
    @Override public Page<Article> searchByKeywordRegex(String regex, Pageable pageable) { return Page.empty(); }
        @Override public Page<Article> searchByKeywordWithTagPriority(String regex, Pageable pageable) { return Page.empty(); }
        // Paging/sorting inherited methods default
        @Override public Page<Article> findAll(Pageable pageable) { return Page.empty(); }
    // The following JPA-specific batch/flush methods are not part of MongoRepository; omit overrides.
        @Override public <S extends Article> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends Article> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends Article> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends Article> Page<S> findAll(org.springframework.data.domain.Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends Article> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends Article> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public List<Article> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends Article, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public <S extends Article> S insert(S entity) { return entity; }
        @Override public <S extends Article> List<S> insert(Iterable<S> entities) { List<S> list = new ArrayList<>(); entities.forEach(list::add); return list; }
        
        // ArticleRepositoryCustom method
        @Override public List<ArticleSourceService.SourceCount> getSourceCounts() { 
            return List.of(); 
        }
        
        // Additional ArticleRepository method
        @Override public List<Article> findAllSourcesOnly() {
            return List.of();
        }
        
        // New method for distinct sources
        @Override public List<Article> findDistinctSourcesRaw() {
            return List.of();
        }
        
        // Missing method that caused compilation error
        @Override public Page<Article> findBySourceProviderOrderByPublishDateDesc(String sourceProvider, Pageable pageable) {
            return Page.empty();
        }
    }

    static class StubGeminiService extends GeminiService {
        private boolean called = false;
        @Override public String generateCompletion(String prompt) {
            called = true;
            // Return OK json for health; not used for analyze (should not be called in missing-case)
            return "{\"status\":\"ok\"}";
        }
    }

    private BiasAnalysisController controllerWith(ArticleRepository repo, GeminiService gemini) throws Exception {
        BiasAnalysisController c = new BiasAnalysisController();
        Field fRepo = BiasAnalysisController.class.getDeclaredField("articleRepository");
        fRepo.setAccessible(true); fRepo.set(c, repo);
        Field fGemini = BiasAnalysisController.class.getDeclaredField("geminiService");
        fGemini.setAccessible(true); fGemini.set(c, gemini);
        return c;
    }

    @Test
    @DisplayName("analyzeBias returns 404 when article not found and does not call Gemini")
    void analyzeBias_missingArticle_404() throws Exception {
        StubGeminiService gemini = new StubGeminiService();
        BiasAnalysisController c = controllerWith(new StubArticleRepo(), gemini);
        ResponseEntity<String> resp = c.analyzeBias("nope", false);
        assertEquals(404, resp.getStatusCode().value());
        assertFalse(gemini.called, "Gemini should not be invoked for missing article path");
    }

    @Test
    @DisplayName("healthCheck returns OK JSON containing model status")
    void healthCheck_ok() throws Exception {
        StubGeminiService gemini = new StubGeminiService();
        BiasAnalysisController c = controllerWith(new StubArticleRepo(), gemini);
        ResponseEntity<String> resp = c.healthCheck();
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("status"));
        assertTrue(gemini.called, "Gemini should be invoked for health check");
    }
}