package com.perplexity.newsaggregator;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fails if any previously removed legacy class names are reintroduced.
 */
class LegacyGuardTest {

    private static final List<String> FORBIDDEN = List.of(
        "BiasAnalysisService", "BiasAnalysisPrompt", "DomainTaxonomyUtil",
        "InterestMappingUtil", "UserInterestExpansionUtil", "UserService",
        "TagFilteringUtil", "TestController"
    );

    @Test
    void noLegacyClassesPresent() throws Exception {
        Path root = Paths.get("src", "main", "java");
        String all = Files.walk(root)
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> {
                    try { return Files.readString(p); } catch (Exception e) { return ""; }
                })
                .collect(Collectors.joining("\n"));
        for (String name : FORBIDDEN) {
            assertFalse(all.contains("class " + name), () -> "Legacy class reintroduced: " + name);
        }
    }
}
