# Design Document

## Overview

The Reading List feature extends the existing news aggregation platform to allow users to create and manage custom lists for organizing news articles by topics. The feature integrates seamlessly with the current architecture, leveraging the existing Article and User entities while adding new ReadingList functionality. Users can create lists like "US War" or "Movies", add articles from the dashboard, and view articles in a dedicated page with the same visual layout as the main dashboard.

## Architecture

### Frontend Architecture
The reading list feature follows the existing frontend patterns:
- **HTML Pages**: `reading-lists.html` (main lists page) and `reading-list-view.html` (individual list view)
- **JavaScript Module**: `reading-lists.js` following the same patterns as `bookmarks.js` and `profile.js`
- **CSS Integration**: Leverages existing modular CSS files with minimal additions
- **Navigation**: Integrates with the existing nav-header structure used in profile and bookmarks pages

### Backend Architecture
The feature extends the current Spring Boot + MongoDB architecture:
- **New Entity**: `ReadingList` document in MongoDB
- **Enhanced Entity**: Add `readingListIds` field to existing `User` entity
- **New Controller**: `ReadingListController` following existing REST patterns
- **New Service**: `ReadingListService` for business logic
- **New Repository**: `ReadingListRepository` extending `MongoRepository`

### Data Flow
1. User creates reading list → POST to `/api/reading-lists`
2. User adds article to list → PUT to `/api/reading-lists/{id}/articles/{articleId}`
3. User views lists → GET to `/api/reading-lists`
4. User views list articles → GET to `/api/reading-lists/{id}/articles`

## Components and Interfaces

### Backend Components

#### ReadingList Entity
```java
@Document(collection = "reading_lists")
public class ReadingList {
    @Id
    private String id;
    
    @Indexed
    private String userId;
    
    private String name;
    private String colorTheme;
    private int displayOrder;
    private Date createdAt;
    private Date updatedAt;
    
    @Indexed
    private List<String> articleIds = new ArrayList<>();
}
```

#### ReadingListController
- `GET /api/reading-lists` - Get user's reading lists
- `POST /api/reading-lists` - Create new reading list
- `PUT /api/reading-lists/{id}` - Update reading list name/color
- `DELETE /api/reading-lists/{id}` - Delete reading list
- `PUT /api/reading-lists/{id}/articles/{articleId}` - Add article to list
- `DELETE /api/reading-lists/{id}/articles/{articleId}` - Remove article from list
- `GET /api/reading-lists/{id}/articles` - Get articles in reading list
- `PUT /api/reading-lists/reorder` - Update list display order

#### ReadingListService
- Business logic for CRUD operations
- Validation for duplicate names, ownership checks
- Article existence validation
- Order management for drag-and-drop

### Frontend Components

#### ReadingListsManager Class
```javascript
class ReadingListsManager {
    constructor() {
        this.baseUrl = window.API_BASE_URL;
        this.readingLists = [];
        this.init();
    }
    
    // Core methods
    loadReadingLists()
    createReadingList(name, colorTheme)
    updateReadingList(id, updates)
    deleteReadingList(id)
    addArticleToList(listId, articleId)
    removeArticleFromList(listId, articleId)
    reorderLists(newOrder)
}
```

#### ReadingListViewManager Class
```javascript
class ReadingListViewManager {
    constructor() {
        this.baseUrl = window.API_BASE_URL;
        this.currentList = null;
        this.articles = [];
        this.init();
    }
    
    // Core methods
    loadReadingList(listId)
    loadArticles()
    removeArticle(articleId)
    renderArticles()
}
```

### UI Components

#### Reading Lists Page (`reading-lists.html`)
- Header with "Create New List" button
- Grid of reading list cards showing:
  - List name with color theme
  - Article count
  - Edit/Delete buttons
  - Drag handles for reordering
- Empty state when no lists exist
- Create/Edit modal for list name and color selection

#### Reading List View Page (`reading-list-view.html`)
- Header with list name and close button (top right)
- Articles grid using existing `articles-grid` CSS class
- Article cards identical to dashboard layout
- Remove buttons on each article card
- Empty state when no articles in list

#### Color Theme System
Predefined color options:
- Blue (default): `#3b82f6`
- Green: `#10b981`
- Purple: `#8b5cf6`
- Red: `#ef4444`
- Orange: `#f59e0b`
- Pink: `#ec4899`
- Indigo: `#6366f1`
- Teal: `#14b8a6`

## Data Models

### ReadingList Document
```json
{
  "_id": "ObjectId",
  "userId": "user_object_id",
  "name": "US War",
  "colorTheme": "#3b82f6",
  "displayOrder": 0,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-01T00:00:00Z",
  "articleIds": ["article_id_1", "article_id_2"]
}
```

### Enhanced User Entity
```java
// Add to existing User entity
private List<String> readingListIds = new ArrayList<>();
```

### API Response DTOs
```java
// ReadingListSummaryDTO
public class ReadingListSummaryDTO {
    private String id;
    private String name;
    private String colorTheme;
    private int displayOrder;
    private int articleCount;
    private Date createdAt;
    private Date updatedAt;
}

// ReadingListWithArticlesDTO
public class ReadingListWithArticlesDTO {
    private String id;
    private String name;
    private String colorTheme;
    private List<Article> articles;
}
```

## Error Handling

### Backend Error Handling
- **404 Not Found**: Reading list doesn't exist or user doesn't own it
- **400 Bad Request**: Invalid input (empty name, invalid color, etc.)
- **409 Conflict**: Duplicate reading list name for user
- **403 Forbidden**: User trying to access another user's reading list

### Frontend Error Handling
- Network errors with retry options
- Validation errors with inline feedback
- Confirmation dialogs for destructive actions (delete list, remove article)
- Loading states for all async operations
- Graceful degradation when API is unavailable

### Error Messages
- "Reading list name cannot be empty"
- "You already have a reading list with this name"
- "Article is already in this reading list"
- "Failed to load reading lists. Please try again."
- "Are you sure you want to delete this reading list? This action cannot be undone."

## Testing Strategy

### Backend Testing
- **Unit Tests**: Service layer methods for business logic
- **Integration Tests**: Controller endpoints with MockMvc
- **Repository Tests**: MongoDB operations with @DataMongoTest
- **Security Tests**: Authentication and authorization

### Frontend Testing
- **Manual Testing**: User workflows across different browsers
- **Integration Testing**: API communication and error handling
- **UI Testing**: Responsive design and accessibility
- **Cross-browser Testing**: Chrome, Firefox, Safari, Edge

### Test Scenarios
1. **Create Reading List**: Valid names, duplicate names, empty names
2. **Add Articles**: Valid articles, duplicates, non-existent articles
3. **Remove Articles**: Valid removal, last article removal
4. **Delete Lists**: Confirmation flow, cascade deletion
5. **Reorder Lists**: Drag-and-drop functionality
6. **Navigation**: Between lists page and individual list views
7. **Color Themes**: Selection and persistence
8. **Responsive Design**: Mobile and desktop layouts

### Performance Testing
- List loading with large numbers of articles (100+)
- Drag-and-drop performance with many lists
- Article rendering performance in list view
- Memory usage with multiple lists and articles