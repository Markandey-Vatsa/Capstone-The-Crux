# Implementation Plan

- [x] 1. Create backend data model and repository

- [x] 1.1 Create ReadingList entity with MongoDB annotations


  - Define ReadingList class with id, userId, name, colorTheme, displayOrder, createdAt, updatedAt, articleIds fields
  - Add proper MongoDB indexes for userId and articleIds
  - _Requirements: 1.1, 1.4, 6.1, 6.2, 7.1, 7.2_



- [ ] 1.2 Create ReadingListRepository interface
  - Extend MongoRepository with custom query methods


  - Add methods for finding by userId and ordered by displayOrder
  - _Requirements: 1.1, 3.1, 7.1, 7.2_




- [ ] 1.3 Update User entity to include readingListIds field
  - Add List<String> readingListIds field to existing User entity
  - Ensure proper initialization as empty ArrayList
  - _Requirements: 1.1, 3.1_



- [ ] 2. Implement backend service layer
- [ ] 2.1 Create ReadingListService class
  - Implement CRUD operations for reading lists
  - Add validation for duplicate names and ownership checks
  - Include methods for adding/removing articles and reordering lists
  - _Requirements: 1.1, 1.4, 1.5, 4.1, 4.2, 4.4, 4.5, 5.1, 5.3, 7.1, 7.2_




- [ ] 2.2 Create DTO classes for API responses
  - Create ReadingListSummaryDTO for list overview
  - Create ReadingListWithArticlesDTO for detailed list view
  - _Requirements: 3.1, 3.3_



- [ ]* 2.3 Write unit tests for ReadingListService
  - Test CRUD operations, validation logic, and error scenarios
  - Mock repository dependencies and test business logic
  - _Requirements: 1.1, 1.4, 1.5, 4.1, 4.2, 4.4, 4.5_


- [ ] 3. Create REST API endpoints
- [ ] 3.1 Create ReadingListController class
  - Implement GET /api/reading-lists endpoint for user's lists
  - Add POST /api/reading-lists for creating new lists
  - Include PUT /api/reading-lists/{id} for updating list details
  - Add DELETE /api/reading-lists/{id} for removing lists
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 4.1, 4.2, 4.3, 4.4, 4.5_




- [ ] 3.2 Implement article management endpoints
  - Add PUT /api/reading-lists/{id}/articles/{articleId} for adding articles
  - Include DELETE /api/reading-lists/{id}/articles/{articleId} for removing articles
  - Create GET /api/reading-lists/{id}/articles for fetching list articles


  - _Requirements: 2.1, 2.2, 2.3, 3.2, 3.3, 5.1, 5.2, 5.3, 5.4_

- [ ] 3.3 Add list reordering endpoint
  - Implement PUT /api/reading-lists/reorder for updating display order

  - Handle array of list IDs with new order positions
  - _Requirements: 7.1, 7.2, 7.3_

- [ ]* 3.4 Write integration tests for ReadingListController
  - Test all endpoints with MockMvc and authentication

  - Verify proper error handling and response formats
  - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 7.1_

- [x] 4. Create reading lists main page

- [x] 4.1 Create reading-lists.html page structure

  - Build HTML page with nav-header matching profile/bookmarks layout
  - Add main content area with lists container and empty state
  - Include create list modal structure
  - _Requirements: 1.1, 3.1, 6.1, 6.5_




- [ ] 4.2 Implement ReadingListsManager JavaScript class
  - Create class following BookmarksManager pattern
  - Add methods for loading, creating, updating, and deleting lists
  - Include authentication check and API communication


  - _Requirements: 1.1, 1.2, 1.3, 3.1, 4.1, 4.2, 4.3, 4.4, 4.5, 6.1_

- [ ] 4.3 Add list rendering and interaction functionality
  - Implement renderReadingLists method to display list cards

  - Add color theme display and article count
  - Include edit/delete button handlers
  - _Requirements: 3.1, 3.5, 4.1, 4.2, 6.1, 6.2, 6.3_

- [x] 4.4 Implement create and edit list modals

  - Add modal for creating new lists with name and color selection
  - Include inline editing for list names
  - Add color theme picker with predefined options
  - _Requirements: 1.1, 1.2, 1.3, 4.1, 4.2, 4.3, 6.1, 6.2, 6.3_




- [ ] 5. Add drag-and-drop reordering functionality
- [ ] 5.1 Implement drag-and-drop for list reordering
  - Add drag handles to list cards
  - Implement drag-and-drop event handlers


  - Update display order via API call
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_


- [x] 6. Create individual reading list view page

- [ ] 6.1 Create reading-list-view.html page structure
  - Build HTML page with header containing list name and close button
  - Add articles container using existing articles-grid CSS class
  - Include empty state for lists with no articles
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 5.4, 5.5_


- [ ] 6.2 Implement ReadingListViewManager JavaScript class
  - Create class for managing individual list view
  - Add methods for loading list details and articles

  - Include article removal functionality

  - _Requirements: 3.2, 3.3, 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 6.3 Add article rendering with remove functionality
  - Render articles using existing article card template
  - Add remove buttons to each article card

  - Implement remove article confirmation and API call
  - _Requirements: 3.3, 5.1, 5.2, 5.3, 5.4_

- [x] 6.4 Implement close button navigation


  - Add close button in top right corner of page

  - Handle navigation back to reading lists page
  - Maintain proper browser history
  - _Requirements: 3.5, 6.2, 6.4_

- [x] 7. Integrate with existing dashboard functionality

- [ ] 7.1 Add "Add to Reading List" functionality to dashboard
  - Modify existing article cards to include reading list button
  - Create dropdown showing user's reading lists
  - Implement add article to list API call with confirmation
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 7.2 Update navigation menu
  - Add "Reading Lists" link to nav-header in all pages
  - Ensure proper active state highlighting
  - Update navigation styling to accommodate new menu item
  - _Requirements: 6.1, 6.5_

- [ ] 8. Add CSS styling and responsive design
- [ ] 8.1 Create reading list specific CSS styles
  - Add styles for list cards with color themes
  - Include drag-and-drop visual feedback
  - Style create/edit modals and forms
  - _Requirements: 6.1, 6.2, 6.3, 7.1, 7.2_

- [ ] 8.2 Ensure responsive design compatibility
  - Test and adjust layouts for mobile devices
  - Ensure drag-and-drop works on touch devices
  - Verify modal responsiveness
  - _Requirements: 6.5, 7.1, 7.2_

- [ ] 9. Implement error handling and user feedback
- [ ] 9.1 Add comprehensive error handling
  - Implement try-catch blocks for all API calls
  - Add user-friendly error messages for common scenarios
  - Include loading states for async operations
  - _Requirements: 1.4, 1.5, 2.4, 4.4, 4.5_

- [ ] 9.2 Add confirmation dialogs for destructive actions
  - Implement delete list confirmation modal
  - Add remove article confirmation
  - Include proper cancel/confirm button handling
  - _Requirements: 4.4, 4.5, 5.2, 5.3_

- [ ] 10. Final integration and testing
- [ ] 10.1 Test complete user workflow
  - Verify create list → add articles → view list → remove articles flow
  - Test navigation between all pages
  - Ensure proper authentication and authorization
  - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1_

- [ ] 10.2 Verify color theme persistence and display
  - Test color theme selection and saving
  - Ensure themes display correctly across all views
  - Verify theme consistency in list cards and headers
  - _Requirements: 6.1, 6.2, 6.3, 6.4_