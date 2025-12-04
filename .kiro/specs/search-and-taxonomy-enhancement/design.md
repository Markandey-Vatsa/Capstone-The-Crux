# Design Document

## Overview

This design outlines minimal modifications to the existing search and taxonomy systems to correct search priority order, enhance keyword tag accuracy through confidence metrics, improve domain mapping, and provide automated domain assignment capabilities. The design preserves the current architecture while implementing targeted enhancements.

## Architecture

### Current System Components (Preserved)
- **ArticleSearchService**: Handles search operations
- **ArticleRepository**: Database access layer with custom queries
- **ArticleTaggingService**: Manages AI-powered tagging operations
- **GroqService**: AI service for keyword generation
- **TaxonomyUtil**: Utility for taxonomy operations

### Enhanced Components
- **Search Priority Logic**: Modified query order in repository methods
- **Confidence Validation**: Enhanced keyword scoring in GroqService
- **Domain Assignment Script**: New Python utility for batch processing

## Components and Interfaces

### 1. Search Priority Enhancement

**Modified Component**: `ArticleRepository.searchByKeywordWithTagPriority()`

**Current Query Order**:
```mongodb
{ $or: [
  { 'domainCategories': { $elemMatch: { $regex: ?0, $options: 'i' } } },
  { 'tags': { $elemMatch: { $regex: ?0, $options: 'i' } } },
  { 'title': { $regex: ?0, $options: 'i' } },
  { 'summary': { $regex: ?0, $options: 'i' } },
  { 'content': { $regex: ?0, $options: 'i' } }
]}
```

**Enhanced Query Order**:
```mongodb
{ $or: [
  { 'tags': { $elemMatch: { $regex: ?0, $options: 'i' } } },
  { 'domainCategories': { $elemMatch: { $regex: ?0, $options: 'i' } } },
  { 'title': { $regex: ?0, $options: 'i' } },
  { 'summary': { $regex: ?0, $options: 'i' } }
]}
```

**Design Rationale**: Reorder search priority to match user requirements (domains first, then keyword tags, then content) and remove content field from search to focus on title/summary.

### 2. Confidence Metric Implementation

**Enhanced Component**: `GroqService` keyword generation methods

**Multiplicative Confidence Formula**:
```
confidence_score = word_present_in_content * relevance_to_context
where:
- word_present_in_content: 1.0 if exact match, 0.8 if partial, 0.0 if absent
- relevance_to_context: 0.0 to 1.0 based on semantic relevance
```

**Word Count Threshold**: Configure minimum word count (suggested: 50 words) below which no keyword tagging occurs.

**Implementation Approach**:
- Modify existing `generateTags()` method to include confidence calculation
- Add configuration property for word count threshold
- Enhance logging to track confidence scores
- Filter keywords below minimum confidence threshold (suggested: 0.6)

### 3. Enhanced Domain Mapping

**Modified Component**: `ArticleTaggingService` domain assignment logic

**Current Flow**:
1. Generate keyword tags via Groq
2. Map tags to domains via `mapTagsToDomains()`
3. Save both keyword tags and domains

**Enhanced Flow**:
1. Check article word count against threshold
2. If sufficient, generate keyword tags with confidence scores
3. Filter keywords by confidence threshold
4. Map validated keywords to domains using taxonomy
5. Add unmapped keywords to pending taxonomy
6. Save validated keyword tags and mapped domains

**Taxonomy Lookup Enhancement**:
- Search both base taxonomy and custom taxonomy files
- Case-insensitive keyword matching
- Support partial keyword matching for compound terms
- Prevent duplicate entries in pending taxonomy

### 4. Domain Assignment Python Script

**New Component**: `backend/scripts/domain_reassignment.py`

**Script Architecture**:
```python
class DomainReassignmentScript:
    def __init__(self):
        self.mongo_client = MongoClient(connection_string)
        self.taxonomy_loader = TaxonomyLoader()
    
    def process_articles_without_domains(self):
        # Find articles with empty tags array
        # For each article, check keyword tags against taxonomy
        # Assign domains if matches found
        # Update database and log results
```

**Database Connection**:
- Read MongoDB connection from environment variables or config file
- Use same connection pattern as Java application
- Handle connection errors gracefully

**Processing Logic**:
1. Query articles where `tags` field is empty or null
2. Load current taxonomy (base + custom)
3. For each article's `domainCategories`, check taxonomy matches
4. Assign corresponding domains to `tags` field
5. Update article in database
6. Log processing statistics

## Data Models

### Enhanced Article Processing Flow

**Input**: Raw article content
**Processing Steps**:
1. **Word Count Check**: Skip if below threshold
2. **Keyword Generation**: Generate with confidence scores
3. **Confidence Filtering**: Keep only high-confidence keywords
4. **Domain Mapping**: Map keywords to domains via taxonomy
5. **Pending Taxonomy**: Store unmapped keywords
6. **Database Update**: Save validated tags and domains

**Output**: Article with validated keyword tags and mapped domains

### Taxonomy File Structure (Preserved)

**Base Taxonomy**: `backend/src/main/resources/taxonomy/base-taxonomy.json`
**Custom Taxonomy**: `backend/taxonomy-custom.json`
**Pending Taxonomy**: `backend/taxonomy-pending.jsonl` (format preserved)

## Error Handling

### Search Enhancement Error Handling
- **MongoDB Query Errors**: Fall back to original search method
- **Regex Compilation Errors**: Log error and use simple text search
- **Empty Results**: Gracefully return empty page

### Confidence Metric Error Handling
- **API Failures**: Skip confidence calculation, use existing keywords
- **Invalid Confidence Scores**: Default to 0.5 confidence
- **Threshold Configuration Errors**: Use default threshold values

### Domain Assignment Script Error Handling
- **Database Connection Failures**: Retry with exponential backoff
- **Taxonomy File Errors**: Log error and continue with available taxonomy
- **Article Update Failures**: Log failed articles for manual review
- **Memory Issues**: Process articles in configurable batches

## Testing Strategy

### Unit Testing Focus
- **Search Priority**: Verify correct query order in repository tests
- **Confidence Calculation**: Test multiplicative scoring logic
- **Taxonomy Mapping**: Test keyword to domain mapping accuracy
- **Word Count Threshold**: Verify articles below threshold are skipped

### Integration Testing
- **End-to-End Search**: Test complete search flow with priority order
- **Tagging Pipeline**: Test complete article processing with confidence metrics
- **Domain Assignment**: Test Python script with test database

### Performance Testing
- **Search Performance**: Ensure query reordering doesn't impact performance
- **Confidence Calculation**: Monitor API call overhead
- **Batch Processing**: Test Python script with large article volumes

### Manual Testing
- **Search Relevance**: Verify search results match expected priority
- **Keyword Quality**: Review generated keywords for accuracy
- **Domain Assignment**: Verify correct domain mapping from keywords