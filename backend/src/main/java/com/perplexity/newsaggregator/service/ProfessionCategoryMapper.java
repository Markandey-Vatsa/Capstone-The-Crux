package com.perplexity.newsaggregator.service;

import java.util.List;
import java.util.Map;

/**
 * Maps a user's profession to supplemental taxonomy categories
 * that should augment their explicit interests in the personalized feed.
 */
public final class ProfessionCategoryMapper {

    private static final Map<String, List<String>> MAP = Map.ofEntries(
        // Finance & business roles
        Map.entry("Stock Trader", List.of("Stock Market","Economy","Personal Finance")),
        Map.entry("Investor", List.of("Stock Market","Startups & Venture Capital","Economy")),
        Map.entry("Business Owner", List.of("Corporate News","Economy","Startups & Venture Capital")),
        Map.entry("Entrepreneur", List.of("Startups & Venture Capital","Economy","Corporate News")),
        // Technology / engineering
        Map.entry("IT Professional", List.of("Artificial Intelligence","Cybersecurity","Gadgets & Devices")),
        Map.entry("Software Engineer", List.of("Artificial Intelligence","Cybersecurity","Gadgets & Devices")),
        Map.entry("Engineer", List.of("Clean Energy","Artificial Intelligence","Space & Astronomy")),
        Map.entry("Data Scientist", List.of("Artificial Intelligence","Medical Research","Public Health")),
        // Health & science
        Map.entry("Healthcare Professional", List.of("Public Health","Medical Research","Biotechnology")),
        Map.entry("Doctor", List.of("Public Health","Medical Research","Biotechnology")),
        Map.entry("Researcher", List.of("Medical Research","Climate & Environment","Biotechnology")),
        // Agriculture & environment
        Map.entry("Farmer", List.of("Agriculture","Climate & Environment","Economy")),
        Map.entry("Environmental Scientist", List.of("Climate & Environment","Wildlife & Nature","Clean Energy")),
        // Legal & governance
        Map.entry("Lawyer", List.of("Law & Justice","Government Policy","Human Rights")),
        Map.entry("Government Employee", List.of("Government Policy","Indian Politics","International Politics")),
        Map.entry("Policy Analyst", List.of("Government Policy","Human Rights","Law & Justice")),
        // Education & media
        Map.entry("Educator / Student", List.of("Education","Public Health","Climate & Environment")),
        Map.entry("Student", List.of("Education","Public Health","Artificial Intelligence")),
        Map.entry("Journalist", List.of("Indian Politics","International Politics","Human Rights")),
        // Sports & lifestyle
        Map.entry("Athlete", List.of("Cricket","Football","Tennis")),
        Map.entry("Coach", List.of("Cricket","Football","Tennis")),
        Map.entry("Automotive Professional", List.of("Automobiles","Clean Energy","Economy"))
    );

    private ProfessionCategoryMapper() {}

    public static List<String> map(String profession) {
        if (profession == null) return List.of();
        return MAP.getOrDefault(profession.trim(), List.of());
    }
}
