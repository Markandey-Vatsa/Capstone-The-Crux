package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.dto.ChatResponse;
import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.repository.ArticleRepository;
import com.perplexity.newsaggregator.service.GeminiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private GeminiService geminiService;

    /**
     * Simple chat endpoint for asking questions about a specific article.
     * Request body: { "message": "..." }
     * Response: { "reply": "..." }
     */
    @PostMapping("/news/{id}/chat")
    public ResponseEntity<Object> chatAboutArticle(@PathVariable String id,
        @RequestBody java.util.Map<String, Object> body) {
        try {
            Optional<Article> opt = articleRepository.findById(id);
            if (opt.isEmpty()) {
                logger.warn("Chat request for missing article id={}", id);
                return ResponseEntity.notFound().build();
            }

            String userMessage = body == null ? null : (String) body.get("message");
            if (userMessage == null || userMessage.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Empty message"));
            }

            Article article = opt.get();
            String rawContent = article.getContent() != null ? article.getContent() : article.getSummary();
            if (rawContent == null)
                rawContent = "";

            // Build a focused prompt instructing Gemini to answer conversationally about
            // the article
            String prompt = "You are a helpful assistant. Use the ARTICLE CONTEXT provided to answer the user's question. Be concise and reference the article directly when possible. If the answer is not present in the article, say you don't know and suggest where to look.\n\n"
                    +
                    "ARTICLE TITLE: " + article.getTitle() + "\n\n" +
                    "ARTICLE CONTENT: " + rawContent.replaceAll("\r?\n+", " ") + "\n\n" +
                    "USER QUESTION: " + userMessage + "\n\n" +
                    "Respond with a short plain-text answer (no JSON wrapper).";

            logger.info("Dispatching chat prompt to Gemini for article id={}", id);
            String aiReply = geminiService.generateCompletion(prompt);

            if (aiReply == null || aiReply.trim().isEmpty()) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Empty response from AI"));
            }

            // If Gemini returned an error-style JSON string, pass it through
            if (aiReply.trim().startsWith("{\"error\"")) {
                return ResponseEntity.internalServerError().body(Map.of("error", "AI service returned an error."));
            }

            return ResponseEntity.ok(new ChatResponse(aiReply));

        } catch (Exception e) {
            logger.error("Chat error for article id={}: {}", id, e.getMessage(), e);
            String msg = e.getMessage() == null ? "unknown" : e.getMessage().replace("\"", "'");
            return ResponseEntity.internalServerError().body(Map.of("error", "Chat failed: " + msg));
        }
    }
}
