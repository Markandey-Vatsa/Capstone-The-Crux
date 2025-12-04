# Design Document

## Overview

The Customizable News Source Pinning feature extends the existing user preferences system to allow users to curate their news source experience. This feature integrates seamlessly into the current profile.html settings page and leverages the existing glassmorphism design system, MongoDB user entity structure, and Spring Boot REST API patterns.

The solution provides a clean, searchable interface for users to select 1-10 preferred news sources, with immediate reflection in the main dashboard sidebar and persistent storage in the user's profile.

## Architecture

### Frontend Architecture

The feature follows the existing modular JavaScript pattern established in `profile.js`, extending the `ProfileManager` class with source management capabilities. The UI integrates into the existing settings sections using the established glass-card component structure.

**Key Components:**
- **Source Management Section**: New settings section following existing `.account-settings.glass-card` pattern
- **Search Interface**: Utilizes existing `.form-control` styling for consistency
- **Source Selection Grid**: Checkbox-based selection using existing preference button patterns
- **Real-time Updates**: Immediate sidebar refresh using existing news.js patterns

### Backend Architecture

The backend extends the current Spring Boot architecture with minimal additions:

**Controller Layer:**
- New endpoints in `UserController.java` for source management
- New endpoints in `NewsController.java` for filtered source lists

**Entity Layer:**
- Extension of existing `User.java` entity with pinned sources field
- Leverages existing MongoDB document structure

**Service Layer:**
- Extension of existing `NewsService.java` for source filtering logic
- Reuse of existing `UserRepository` patterns

## Components and Interfaces

### Frontend Components

#### 1. Source Management Section (HTML)
```html
<div class="account-settings glass-card" id="source-management-section">
    <h3>Manage Sources</h3>
    <p>Select 1-10 news sources to display in your sidebar</p>
    
    <div class="setting-item">
        <div class="setting-info" style="width:100%">
            <h4>Available Sources</h4>
            <input type="text" id="source-search" class="form-control" 
                   placeholder="Search news sources...">
            <div id="source-selection-grid" class="source-grid">
                <!-- Dynamically populated source checkboxes -->
            </div>
            <div id="selection-counter" class="selection-info">
                0 of 10 sources selected
            </div>
        </div>
    </div>
    
    <div class="setting-item" style="justify-content:flex-end;">
        <button id="savePinnedSourcesBtn" class="btn btn-primary">Save Sources</button>
        <span id="sourcesStatus" class="status-message"></span>
    </div>
</div>
```

#### 2. Source Management JavaScript (Extension of ProfileManager)
```javascript
// Extension methods for ProfileManager class
initSourceManagement(profile) {
    this.loadAvailableSources();
    this.setupSourceSearch();
    this.displayPinnedSources(profile.pinnedSources || []);
    this.setupSourceSaveHandler();
}

async loadAvailableSources() {
    // Fetch dynamic source list from /api/news/available-sources
}

setupSourceSearch() {
    // Implement real-time filtering of source list
}

async savePinnedSources() {
    // Save selected sources via /api/user/pinned-sources
}
```

#### 3. Sidebar Integration (Extension of news.js)
```javascript
// Extension of existing sidebar population logic
async loadSourcesWithCounts() {
    const user = await this.getCurrentUser();
    if (user && user.pinnedSources && user.pinnedSources.length > 0) {
        // Load only pinned sources
        const sources = await this.fetchSourceCounts(user.pinnedSources);
    } else {
        // Fallback to all sources (existing behavior)
        const sources = await this.fetchAllSourceCounts();
    }
    this.populateSourcesSidebar(sources);
}
```

### Backend Components

#### 1. User Entity Extension
```java
// Addition to existing User.java
@Document(collection = "users")
public class User {
    // ... existing fields ...
    
    // List of pinned news source names (1-10 items)
    private List<String> pinnedSources = new ArrayList<>();
    
    // ... existing methods ...
}
```

#### 2. UserController Extensions
```java
// New endpoints in UserController.java

@GetMapping("/pinned-sources")
public ResponseEntity<?> getPinnedSources(Authentication authentication) {
    // Return user's pinned sources
}

@PutMapping("/pinned-sources")
public ResponseEntity<?> updatePinnedSources(
    @RequestBody Map<String, Object> body, 
    Authentication authentication) {
    // Validate 1-10 sources and save to user profile
}
```

#### 3. NewsController Extensions
```java
// New endpoint in NewsController.java

@GetMapping("/available-sources")
public ResponseEntity<List<String>> getAvailableSources() {
    // Return dynamic list of sources that have articles
    return ResponseEntity.ok(newsService.getAvailableSourceNames());
}
```

#### 4. NewsService Extensions
```java
// New methods in NewsService.java

public List<String> getAvailableSourceNames() {
    // Query database for unique sources with articles
    return articleRepository.findDistinctSources();
}

public List<SourceCount> getSourcesWithCountsFiltered(List<String> pinnedSources) {
    // Return source counts only for pinned sources
}
```

## Data Models

### User Profile Extension
```json
{
  "id": "user123",
  "username": "john_doe",
  "email": "john@example.com",
  "profession": "Software Engineer",
  "interests": ["Technology", "Science"],
  "pinnedSources": ["BBC News", "Reuters", "TechCrunch"]
}
```

### Available Sources Response
```json
{
  "sources": [
    "BBC News",
    "Reuters", 
    "CNN",
    "TechCrunch",
    "The Guardian"
  ],
  "total": 5
}
```

### Pinned Sources Update Request
```json
{
  "pinnedSources": ["BBC News", "Reuters", "TechCrunch"]
}
```

## Error Handling

### Frontend Error Handling
- **Network Errors**: Utilize existing `safeFetch` pattern from ProfileManager
- **Validation Errors**: Real-time validation with visual feedback
- **Selection Limits**: Disable checkboxes when 10 sources selected
- **Empty Selection**: Prevent saving with 0 sources selected

### Backend Error Handling
- **Authentication**: Leverage existing 401 handling patterns
- **Validation**: Return 400 for invalid source counts (not 1-10)
- **Database Errors**: Return 500 with appropriate error messages
- **Source Validation**: Verify selected sources exist in database

### Error Response Format
```json
{
  "error": "Invalid source selection",
  "message": "Must select between 1 and 10 sources",
  "code": "INVALID_SOURCE_COUNT"
}
```

## Testing Strategy

### Frontend Testing
- **Unit Tests**: Test source selection logic and validation
- **Integration Tests**: Test API communication and error handling
- **UI Tests**: Verify search functionality and visual feedback
- **Responsive Tests**: Ensure mobile compatibility

### Backend Testing
- **Unit Tests**: Test service layer source filtering logic
- **Integration Tests**: Test controller endpoints with authentication
- **Database Tests**: Verify MongoDB operations and queries
- **Validation Tests**: Test source count and existence validation

### End-to-End Testing
- **User Flow Tests**: Complete source selection and sidebar update flow
- **Persistence Tests**: Verify settings persist across sessions
- **Edge Case Tests**: Test with no sources, maximum sources, invalid sources

## Implementation Considerations

### Performance Optimization
- **Source Caching**: Cache available sources list for 5 minutes
- **Lazy Loading**: Load source counts only when sidebar is visible
- **Debounced Search**: Implement 300ms debounce on source search
- **Efficient Queries**: Use MongoDB aggregation for source counts

### Security Considerations
- **Authentication**: All endpoints require valid JWT token
- **Authorization**: Users can only modify their own pinned sources
- **Input Validation**: Sanitize and validate all source names
- **Rate Limiting**: Prevent excessive API calls

### Scalability Considerations
- **Database Indexing**: Index source field for efficient queries
- **Pagination**: Support pagination for large source lists
- **Caching Strategy**: Implement Redis caching for source counts
- **API Versioning**: Design endpoints for future extensibility

### Backward Compatibility
- **Default Behavior**: Users without pinned sources see all sources
- **Migration Strategy**: Existing users continue with current experience
- **Graceful Degradation**: Feature works without JavaScript (basic functionality)
- **API Compatibility**: New endpoints don't affect existing functionality