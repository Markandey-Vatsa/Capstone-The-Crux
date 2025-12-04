package com.perplexity.newsaggregator.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String username;
    
    @Indexed(unique = true)
    private String email;
    
    private String password;
    
    // List of saved/bookmarked article IDs
    private List<String> savedArticleIds = new ArrayList<>();

    // List of reading list IDs owned by this user
    private List<String> readingListIds = new ArrayList<>();

    // Personalization fields
    private String profession; // single selected profession
    private List<String> interests = new ArrayList<>(); // list of interest/category names
    
    // List of pinned news source names (1-10 items)
    private List<String> pinnedSources = new ArrayList<>();
}
