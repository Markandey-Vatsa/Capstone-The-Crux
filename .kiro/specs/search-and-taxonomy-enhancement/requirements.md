# Requirements Document

## Introduction

This specification addresses enhancements to the search functionality and taxonomy management system to align with the defined process flow. The focus is on correcting search priority order, improving domain mapping based on keyword tags, and creating automated domain assignment capabilities for articles without domains. All changes must be minimal tweaks to existing code structure without major architectural modifications.

## Glossary

- **Keyword_Tags**: AI-generated keywords from Groq based on article content (stored in `domainCategories` field)
- **Domains**: High-level interest categories that map to user interests (stored in `tags` field)
- **Taxonomy_System**: The mapping system that connects keyword tags to domains using base and custom taxonomy files
- **Search_Service**: The article search functionality that processes user queries
- **Domain_Assignment_Script**: Python script that assigns domains to articles based on keyword tags
- **Pending_Taxonomy**: File system for storing unmapped keywords for manual review
- **Confidence_Metric**: Multiplicative scoring system for keyword accuracy validation
- **Word_Count_Threshold**: Minimum article length requirement for keyword tag assignment

## Requirements

### Requirement 1

**User Story:** As a user searching for articles, I want the search to prioritize domains first, then keyword tags, then content, so that I get the most relevant results based on my interests.

#### Acceptance Criteria

1. WHEN a user performs a search, THE Search_Service SHALL search domains first before keyword tags
2. IF no results are found in domains, THEN THE Search_Service SHALL search keyword tags
3. IF no results are found in keyword tags, THEN THE Search_Service SHALL search article title and summary
4. THE Search_Service SHALL maintain case-insensitive matching across all search levels
5. THE Search_Service SHALL return results ordered by publish date descending

### Requirement 2

**User Story:** As the system processing articles, I want keyword tags to be properly mapped to domains using the taxonomy system, so that articles are correctly categorized for personalized feeds.

#### Acceptance Criteria

1. WHEN an article receives keyword tags from Groq, THE Taxonomy_System SHALL attempt to map each keyword to existing domains
2. IF a keyword matches entries in base or custom taxonomy, THEN THE Taxonomy_System SHALL assign the corresponding domain to the article
3. IF a keyword does not match any taxonomy entry, THEN THE Taxonomy_System SHALL add the keyword to the pending taxonomy file
4. THE Taxonomy_System SHALL support multiple domains per article based on multiple keyword matches
5. THE Taxonomy_System SHALL preserve existing domain assignments when processing new keywords

### Requirement 3

**User Story:** As an administrator, I want unmapped keywords to be automatically saved to a pending taxonomy file, so that I can manually review and assign them to appropriate domains.

#### Acceptance Criteria

1. WHEN a keyword tag cannot be mapped to any domain, THE Taxonomy_System SHALL append the keyword to the pending taxonomy file
2. THE Taxonomy_System SHALL prevent duplicate entries in the pending taxonomy file
3. THE Taxonomy_System SHALL maintain the existing file format for pending taxonomy entries
4. THE Taxonomy_System SHALL log when keywords are added to pending taxonomy for monitoring
5. THE Taxonomy_System SHALL continue processing other keywords even if some cannot be mapped

### Requirement 4

**User Story:** As the system processing articles, I want keyword tag assignment to use high accuracy validation, so that only relevant and confident keywords are assigned to articles.

#### Acceptance Criteria

1. WHEN an article has fewer than the Word_Count_Threshold, THE Taxonomy_System SHALL skip keyword tag assignment to save API costs
2. WHEN generating keyword tags, THE Taxonomy_System SHALL use a multiplicative confidence metric instead of additive scoring
3. THE Confidence_Metric SHALL calculate as (Word_Present_In_Content * Relevance_To_Context) for each keyword
4. THE Taxonomy_System SHALL only assign keywords that exceed the minimum confidence threshold
5. THE Taxonomy_System SHALL prevent hallucinated or incorrect keyword assignments through confidence validation
6. THE Taxonomy_System SHALL log confidence scores for monitoring keyword quality

### Requirement 5

**User Story:** As the system administrator, I want a Python script that can assign domains to articles without domains, so that previously untagged articles can be properly categorized when new taxonomy mappings are added.

#### Acceptance Criteria

1. THE Domain_Assignment_Script SHALL identify articles with zero domains assigned
2. WHEN processing an article without domains, THE Domain_Assignment_Script SHALL check if any of its keyword tags now match taxonomy entries
3. IF keyword tags match taxonomy entries, THEN THE Domain_Assignment_Script SHALL assign the corresponding domains to the article
4. IF no keyword tags match taxonomy entries, THEN THE Domain_Assignment_Script SHALL leave the article without domains
5. THE Domain_Assignment_Script SHALL update the database with new domain assignments
6. THE Domain_Assignment_Script SHALL log the number of articles processed and domains assigned
7. THE Domain_Assignment_Script SHALL connect to the MongoDB database using configuration settings

### Requirement 6

**User Story:** As a developer implementing these enhancements, I want to make minimal changes to existing code structure, so that the system remains stable and maintainable.

#### Acceptance Criteria

1. THE implementation SHALL modify existing methods rather than creating new architectural components
2. THE implementation SHALL preserve existing database schema and field names
3. THE implementation SHALL maintain backward compatibility with current API endpoints
4. THE implementation SHALL reuse existing service classes and dependency injection patterns
5. THE implementation SHALL avoid major refactoring of core system components