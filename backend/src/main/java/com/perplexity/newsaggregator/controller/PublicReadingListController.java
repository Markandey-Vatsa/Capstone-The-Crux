package com.perplexity.newsaggregator.controller;

import com.perplexity.newsaggregator.dto.PublicReadingListDTO;
import com.perplexity.newsaggregator.entity.Article;
import com.perplexity.newsaggregator.entity.ReadingList;
import com.perplexity.newsaggregator.entity.User;
import com.perplexity.newsaggregator.repository.UserRepository;
import com.perplexity.newsaggregator.service.ReadingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/public")
public class PublicReadingListController {

    private static final Logger logger = LoggerFactory.getLogger(PublicReadingListController.class);

    @Autowired
    private ReadingListService readingListService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Get public reading list by share token
     */
    @GetMapping("/reading-lists/{shareToken}")
    public ResponseEntity<?> getPublicReadingList(@PathVariable String shareToken) {
        try {
            logger.debug("Getting public reading list for share token");

            ReadingList readingList = readingListService.findByShareToken(shareToken);
            if (readingList == null) {
                logger.warn("Public reading list not found or private for share token");
                return ResponseEntity.status(404).body(Map.of("error", "Reading list not found or private"));
            }

            // Get owner username (safe to expose in public context)
            String ownerUsername = "Unknown User";
            Optional<User> userOpt = userRepository.findById(readingList.getUserId());
            if (userOpt.isPresent()) {
                ownerUsername = userOpt.get().getUsername();
            }

            // Create public DTO without articles (metadata only)
            PublicReadingListDTO publicList = new PublicReadingListDTO(
                readingList.getName(),
                readingList.getColorTheme(),
                ownerUsername,
                readingList.getSharedAt(),
                readingList.getArticleIds().size()
            );

            return ResponseEntity.ok(publicList);

        } catch (Exception e) {
            logger.error("Error retrieving public reading list: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Unable to load reading list"));
        }
    }

    /**
     * Get articles for public reading list
     */
    @GetMapping("/reading-lists/{shareToken}/articles")
    public ResponseEntity<?> getPublicReadingListArticles(@PathVariable String shareToken) {
        try {
            logger.debug("Getting articles for public reading list with share token: {}", shareToken);

            ReadingList readingList = readingListService.findByShareToken(shareToken);
            if (readingList == null) {
                logger.warn("Public reading list not found or private for share token: {}", shareToken);
                return ResponseEntity.status(404).body(Map.of("error", "Reading list not found or private"));
            }

            logger.debug("Found public reading list: {} (ID: {})", readingList.getName(), readingList.getId());

            // Get articles for the reading list
            List<Article> articles = readingListService.getReadingListArticles(readingList.getUserId(), readingList.getId());
            logger.debug("Retrieved {} articles for public reading list", articles.size());

            // Get owner username
            String ownerUsername = "Unknown User";
            Optional<User> userOpt = userRepository.findById(readingList.getUserId());
            if (userOpt.isPresent()) {
                ownerUsername = userOpt.get().getUsername();
                logger.debug("Found owner username: {}", ownerUsername);
            } else {
                logger.warn("Owner user not found for reading list: {}", readingList.getId());
            }

            // Create public DTO with articles
            PublicReadingListDTO publicList = new PublicReadingListDTO(
                readingList.getName(),
                readingList.getColorTheme(),
                ownerUsername,
                readingList.getSharedAt(),
                articles.size(),
                articles
            );

            logger.debug("Returning public reading list DTO with {} articles", articles.size());
            return ResponseEntity.ok(publicList);

        } catch (Exception e) {
            logger.error("Error retrieving public reading list articles: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Unable to load reading list articles"));
        }
    }
}