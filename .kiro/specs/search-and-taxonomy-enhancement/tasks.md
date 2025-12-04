# Implementation Plan

- [x] 1. Fix search priority order in repository queries



  - Modify the `searchByKeywordWithTagPriority` method in ArticleRepository to search domains (tags) first, then keyword tags (domainCategories), then title/summary
  - Remove content field from search query to focus on title and summary only
  - Preserve existing method signature and return type for backward compatibility
  - _Requirements: 1.1, 1.2, 1.3, 6.1, 6.3_



- [ ] 2. Implement confidence-based keyword tag validation
- [ ] 2.1 Add word count threshold configuration
  - Add configuration property for minimum word count threshold in application.properties


  - Modify ArticleTaggingService to check article word count before calling Groq API
  - Skip keyword generation for articles below threshold to save API costs
  - _Requirements: 4.1, 6.2_

- [x] 2.2 Enhance GroqService with multiplicative confidence scoring


  - Modify generateTags method to calculate confidence scores using multiplicative formula
  - Implement word presence detection in article content
  - Add relevance scoring based on semantic context
  - Filter keywords below minimum confidence threshold before returning
  - _Requirements: 4.2, 4.3, 4.4, 4.5_



- [ ] 2.3 Add confidence score logging and monitoring
  - Enhance logging in ArticleTaggingService to track confidence scores
  - Log keyword quality metrics for monitoring hallucination prevention


  - Add debug logging for confidence calculation details
  - _Requirements: 4.6, 6.1_

- [x] 3. Enhance domain mapping and pending taxonomy management


- [ ] 3.1 Improve taxonomy lookup logic
  - Enhance TaxonomyUtil to search both base and custom taxonomy files
  - Implement case-insensitive keyword matching for taxonomy lookup
  - Add support for partial keyword matching for compound terms
  - _Requirements: 2.1, 2.2, 6.1_



- [ ] 3.2 Implement pending taxonomy duplicate prevention
  - Modify pending taxonomy addition logic to check for existing entries
  - Prevent duplicate keywords from being added to pending taxonomy file
  - Maintain existing JSONL format for backward compatibility

  - _Requirements: 3.2, 3.3, 6.2_

- [ ] 3.3 Enhance domain assignment in ArticleTaggingService
  - Update domain mapping logic to use enhanced taxonomy lookup
  - Ensure multiple domains can be assigned based on multiple keyword matches

  - Preserve existing domain assignments when processing new keywords
  - Add unmapped keywords to pending taxonomy with duplicate prevention
  - _Requirements: 2.3, 2.4, 2.5, 3.1, 3.4_

- [ ] 4. Create Python domain reassignment script
- [x] 4.1 Set up Python script structure and database connection


  - Create domain_reassignment.py script in backend/scripts directory
  - Implement MongoDB connection using environment variables or config file
  - Add error handling for database connection failures with retry logic
  - _Requirements: 5.7, 6.4_

- [ ] 4.2 Implement article identification and processing logic
  - Query MongoDB to find articles with empty or null tags array
  - Load current taxonomy data from both base and custom taxonomy files
  - Process articles in configurable batches to handle memory efficiently
  - _Requirements: 5.1, 5.2, 6.4_

- [ ] 4.3 Implement domain assignment and database update logic
  - Check each article's domainCategories against loaded taxonomy
  - Assign corresponding domains to tags field when matches are found
  - Update articles in MongoDB database with new domain assignments
  - Skip articles where no keyword tags match taxonomy entries
  - _Requirements: 5.3, 5.4, 5.5_

- [ ] 4.4 Add comprehensive logging and error handling
  - Log number of articles processed and domains assigned
  - Handle taxonomy file loading errors gracefully
  - Log failed article updates for manual review
  - Add processing statistics and performance metrics
  - _Requirements: 5.6, 6.4_

- [ ]* 5. Add unit tests for enhanced functionality
- [ ]* 5.1 Write tests for search priority order
  - Test that searchByKeywordWithTagPriority returns results in correct priority order
  - Verify domains are searched before keyword tags
  - Test that content field is excluded from search
  - _Requirements: 1.1, 1.2, 1.3_

- [ ]* 5.2 Write tests for confidence metric calculation
  - Test multiplicative confidence scoring formula
  - Test word count threshold enforcement
  - Test keyword filtering based on confidence scores
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ]* 5.3 Write tests for enhanced taxonomy mapping
  - Test case-insensitive keyword matching
  - Test partial keyword matching for compound terms
  - Test pending taxonomy duplicate prevention
  - _Requirements: 2.1, 2.2, 3.2_