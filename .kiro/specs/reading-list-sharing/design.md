# Design Document

## Overview

The Reading List Sharing feature extends the existing reading list functionality to enable public sharing without breaking any existing code. The design follows a non-intrusive approach where sharing capabilities are added as optional enhancements to the current system. The architecture supports dynamic, real-time sharing where shared lists automatically reflect changes made by the owner, similar to how Google Docs or GitHub Gists work.

## Architecture

### Backend Architecture
The sharing feature extends the existing Spring Boot + MongoDB architecture with minimal changes:
- **Enhanced Entity**: Add sharing fields to existing `ReadingList` entity
- **New Public Endpoints**: Create unauthenticated endpoints for public access
- **Extended Service**: Add sharing methods to existing `ReadingListService`
- **Token Management**: Implement secure share token generation and validation

### Frontend Architecture
The sharing feature adds new components while preserving existing functionality:
- **Enhanced Private View**: Add share button and modal to existing reading list view
- **New Public View**: Create separate page for public access with read-only interface
- **Shared Components**: Reuse existing CSS and article rendering logic
- **Progressive Enhancement**: Share features only appear when backend supports them

### Data Flow
**Private List Management (Unchanged):**
1. User manages lists → Existing authenticated endpoints
2. CRUD operations → Existing service methods
3. UI interactions → Existing JavaScript classes

**New Sharing Flow:**
1. User clicks share → POST to `/api/reading-lists/{id}/share`
2. System generates token → Updates `ReadingList` with sharing fields
3. User copies URL → `shared-reading-list.html?token={shareToken}`
4. Viewer accesses URL → GET to `/api/public/reading-lists/{token}`

## Components and Interfaces

### Backend Components

#### Enhanced ReadingList Entity
```java
@Document(collection = "reading_lists")
public class ReadingList {
    // ... existing fields unchanged ...
    
    // New sharing fields (with safe defaults)
    private boolean isPublic = false;
    private String shareToken;
    private Date sharedAt;
}
```

#### New Public Controller
```java
@RestController
@RequestMapping("/api/public")
@CrossOrigin(origins = {"*"}) // Allow public access
public class PublicReadingListController {
    
    @GetMapping("/reading-lists/{shareToken}")
    public ResponseEntity<?> getPublicReadingList(@PathVariable String shareToken)
    
    @GetMapping("/reading-lists/{shareToken}/articles") 
    public ResponseEntity<?> getPublicReadingListArticles(@PathVariable String shareToken)
}
```

#### Extended ReadingListController
```java
// Add to existing controller (no changes to existing methods)
@PutMapping("/{listId}/share")
public ResponseEntity<?> shareReadingList(@PathVariable String listId, Authentication auth)

@DeleteMapping("/{listId}/share") 
public ResponseEntity<?> unshareReadingList(@PathVariable String listId, Authentication auth)
```

#### Extended ReadingListService
```java
// Add to existing service (no changes to existing methods)
public String generateShareToken(String userId, String listId)
public void makeListPrivate(String userId, String listId)
public ReadingList findByShareToken(String shareToken)
public PublicReadingListDTO getPublicReadingList(String shareToken)
```

### Frontend Components

#### Enhanced Reading List View
**File**: `reading-list-view.html` (minimal additions)
```html
<!-- Add to existing header actions -->
<button id="shareBtn" class="btn btn-secondary">
    <i class="fas fa-share"></i>
    Share
</button>

<!-- Add sharing modal (hidden by default) -->
<div id="shareModal" class="modal-overlay" style="display: none;">
    <!-- Share modal content -->
</div>
```

#### New Public Reading List View
**File**: `shared-reading-list.html` (completely new)
```html
<!DOCTYPE html>
<html lang="en">
<head>
    <!-- Reuse existing CSS -->
    <link rel="stylesheet" href="css/reading-list-view.css">
    <link rel="stylesheet" href="css/shared-list.css">
</head>
<body>
    <!-- Public header with attribution -->
    <header class="shared-list-header">
        <h1 id="listTitle">Loading...</h1>
        <p class="attribution">Shared by <span id="ownerName">...</span></p>
    </header>
    
    <!-- Reuse existing article grid structure -->
    <main class="main-content">
        <div id="articlesContainer" class="articles-grid">
            <!-- Articles rendered here -->
        </div>
    </main>
</body>
</html>
```

#### JavaScript Classes

**Enhanced ReadingListViewManager** (extend existing)
```javascript
// Add to existing class
class ReadingListViewManager {
    // ... existing methods unchanged ...
    
    // New sharing methods
    showShareModal()
    toggleListPrivacy(isPublic)
    generateShareUrl()
    copyShareUrl()
}
```

**New SharedReadingListView** (completely new)
```javascript
class SharedReadingListView {
    constructor() {
        this.baseUrl = window.API_BASE_URL;
        this.shareToken = this.getShareTokenFromUrl();
        this.listData = null;
        this.articles = [];
    }
    
    async loadPublicList()
    renderPublicView()
    createPublicArticleCard(article) // No edit buttons
    showPrivateMessage()
}
```

### Data Models

#### Enhanced ReadingList Document
```json
{
  "_id": "ObjectId",
  "userId": "user_object_id",
  "name": "AI Technology News",
  "colorTheme": "#3b82f6",
  "displayOrder": 0,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-01T00:00:00Z",
  "articleIds": ["article_id_1", "article_id_2"],
  
  // New sharing fields
  "isPublic": true,
  "shareToken": "abc123-def456-ghi789-jkl012",
  "sharedAt": "2025-01-01T12:00:00Z"
}
```

#### Public API Response DTOs
```java
public class PublicReadingListDTO {
    private String name;
    private String colorTheme;
    private String ownerUsername;
    private Date sharedAt;
    private List<Article> articles;
    // No sensitive data (userId, private fields)
}

public class ShareTokenResponse {
    private String shareToken;
    private String shareUrl;
    private boolean isPublic;
    private Date sharedAt;
}
```

### URL Structure

**Private URLs (Existing - Unchanged):**
```
/reading-lists.html
/reading-list-view.html?id={listId}
```

**New Public URLs:**
```
/shared-reading-list.html?token={shareToken}
```

**API Endpoints:**
```
// Existing (unchanged)
GET  /api/reading-lists
POST /api/reading-lists
GET  /api/reading-lists/{id}/articles

// New sharing endpoints
PUT    /api/reading-lists/{id}/share
DELETE /api/reading-lists/{id}/share

// New public endpoints (no auth required)
GET /api/public/reading-lists/{shareToken}
GET /api/public/reading-lists/{shareToken}/articles
```

## Security Design

### Share Token Security
- **Token Format**: UUID v4 (128-bit, cryptographically secure)
- **Token Storage**: Indexed field in MongoDB for fast lookup
- **Token Persistence**: Same token reused when toggling public/private
- **Token Validation**: Server validates token existence and list public status

### Access Control
- **Public Endpoints**: No authentication required, rate-limited
- **Private Endpoints**: Existing JWT authentication unchanged
- **Owner Verification**: Only list owner can change sharing settings
- **Privacy Enforcement**: Private lists immediately inaccessible via share URL

### Data Protection
- **Public Data**: Only non-sensitive list and article data exposed
- **User Privacy**: Owner username shown, but no email or personal data
- **Article Content**: Only title, summary, URL, date - no user-specific data
- **Error Handling**: Generic error messages to prevent information leakage

## User Interface Design

### Share Modal Design
```
┌─────────────────────────────────────┐
│ Share Reading List                  │
├─────────────────────────────────────┤
│ ○ Private (Only you can view)       │
│ ● Public (Anyone with link can view)│
│                                     │
│ Share URL:                          │
│ ┌─────────────────────────┐ [Copy]  │
│ │ https://app.com/shared- │         │
│ │ reading-list.html?token=│         │
│ │ abc123-def456...        │         │
│ └─────────────────────────┘         │
│                                     │
│ [Cancel]              [Save]        │
└─────────────────────────────────────┘
```

### Public View Design
```
┌─────────────────────────────────────┐
│ AI Technology News                  │
│ Shared by John Doe                  │
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐ │
│ │ [Article Card - Read Only]      │ │
│ │ Title: "Latest AI Breakthrough" │ │
│ │ Summary: "Researchers at..."    │ │
│ │ [Read Full Article]             │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ [Article Card - Read Only]      │ │
│ │ ...                             │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

## Error Handling

### Backend Error Scenarios
- **Invalid Share Token**: Return 404 with generic "not found" message
- **Private List Access**: Return 404 (don't reveal list exists)
- **Missing List**: Return 404 with generic message
- **Server Errors**: Return 500 with generic error message

### Frontend Error Handling
- **Network Errors**: Show retry option with user-friendly message
- **Invalid URLs**: Redirect to main app with error notification
- **Private Lists**: Show "This list is private or no longer available"
- **Loading Failures**: Display loading error with refresh option

### Error Messages
- **Private/Invalid List**: "This reading list is private or no longer available"
- **Network Error**: "Unable to load reading list. Please check your connection and try again"
- **Generic Error**: "Something went wrong. Please try again later"

## Performance Considerations

### Caching Strategy
- **Public Lists**: Cache public list data for 5 minutes
- **Article Data**: Reuse existing article caching mechanisms
- **Share Tokens**: Index share tokens for fast database lookups
- **Static Assets**: Leverage existing CSS/JS caching

### Database Optimization
- **Indexes**: Add index on `shareToken` field for fast public lookups
- **Queries**: Optimize public queries to fetch only necessary data
- **Connection Pooling**: Reuse existing database connection management

### Frontend Performance
- **Code Reuse**: Share CSS and utility functions between private/public views
- **Lazy Loading**: Load public view assets only when needed
- **Progressive Enhancement**: Share features load after core functionality

## Testing Strategy

### Backend Testing
- **Unit Tests**: Test sharing service methods and token generation
- **Integration Tests**: Test public API endpoints without authentication
- **Security Tests**: Verify private lists are inaccessible via share URLs
- **Performance Tests**: Test public endpoint response times

### Frontend Testing
- **Component Tests**: Test share modal functionality and public view rendering
- **Integration Tests**: Test end-to-end sharing workflow
- **Cross-browser Tests**: Verify public view works across browsers
- **Mobile Tests**: Test responsive design on various devices

### Test Scenarios
- **Share List**: Create public list, verify URL generation and access
- **Privacy Toggle**: Make list private, verify access is blocked
- **Dynamic Updates**: Modify shared list, verify changes appear for viewers
- **Error Handling**: Test invalid tokens, private lists, network failures
- **Security**: Attempt to access private lists, verify proper blocking