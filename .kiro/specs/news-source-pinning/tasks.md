# Implementation Plan

- [-] 1. Extend User entity and repository for pinned sources

  - Add `pinnedSources` field to User.java entity with proper validation
  - Create database migration-friendly field with default empty list
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 2. Implement backend API endpoints for source management
- [ ] 2.1 Create available sources endpoint in NewsController
  - Implement `/api/news/available-sources` endpoint that returns dynamic list of sources with articles
  - Add service method to query distinct sources from article repository
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 2.2 Create pinned sources endpoints in UserController
  - Implement GET `/api/user/pinned-sources` endpoint to retrieve user's pinned sources
  - Implement PUT `/api/user/pinned-sources` endpoint to update user's pinned sources with validation
  - Add validation for 1-10 source limit and source existence
  - _Requirements: 1.2, 1.3, 6.1, 6.2, 6.3_

- [ ] 2.3 Extend NewsService for filtered source operations
  - Add method to get available source names from database
  - Add method to get source counts filtered by pinned sources list
  - Implement caching strategy for source lists
  - _Requirements: 4.1, 4.2, 4.3_

- [ ] 3. Create source management UI components
- [ ] 3.1 Add source management section to profile.html
  - Create new settings section following existing glass-card pattern
  - Add search input field with consistent form-control styling
  - Create source selection grid container with proper layout
  - Add selection counter and save button with existing button styles
  - _Requirements: 1.1, 2.1, 2.4, 7.1, 7.3_

- [ ] 3.2 Implement source management CSS styles
  - Create source grid layout styles consistent with existing preference buttons
  - Add checkbox styling that matches existing form elements
  - Implement selection counter and validation message styles
  - Ensure responsive design for mobile devices
  - _Requirements: 7.1, 7.2, 7.3_

- [ ] 4. Extend ProfileManager JavaScript for source management
- [ ] 4.1 Add source management initialization to ProfileManager
  - Extend displayProfile method to handle pinnedSources data
  - Create initSourceManagement method to set up source selection UI
  - Add loadAvailableSources method to fetch dynamic source list
  - _Requirements: 2.1, 2.2, 3.1, 3.2_

- [ ] 4.2 Implement source search and selection functionality
  - Create setupSourceSearch method with debounced filtering
  - Implement source checkbox selection with real-time counter updates
  - Add validation for minimum 1 and maximum 10 source selection
  - Create visual feedback for selection limits
  - _Requirements: 2.2, 2.3, 2.4, 6.1, 6.2_

- [ ] 4.3 Implement source preferences saving
  - Create savePinnedSources method with API integration
  - Add error handling and success feedback using existing toast patterns
  - Implement validation and user feedback for save operations
  - _Requirements: 1.4, 6.3, 6.4_

- [ ] 5. Update main dashboard sidebar integration
- [ ] 5.1 Modify news.js to support filtered sources
  - Extend loadSourcesWithCounts method to check for user's pinned sources
  - Implement conditional loading of either pinned sources or all sources
  - Update populateSourcesSidebar to handle filtered source lists
  - _Requirements: 1.4, 4.1, 4.2_

- [ ] 5.2 Add user authentication check for source filtering
  - Implement getCurrentUser method to fetch user profile data
  - Add fallback to show all sources for unauthenticated users
  - Handle authentication errors gracefully
  - _Requirements: 4.4, 5.1, 5.2_

- [ ]* 5.3 Write unit tests for source filtering logic
  - Create tests for source selection validation
  - Test API endpoint responses and error handling
  - Verify sidebar filtering logic with different user states
  - _Requirements: 1.2, 1.3, 4.1, 4.2_

- [ ] 6. Implement error handling and validation
- [ ] 6.1 Add frontend validation and user feedback
  - Implement real-time validation for source selection limits
  - Add visual indicators for validation states (error/success)
  - Create user-friendly error messages for common scenarios
  - _Requirements: 6.1, 6.2, 6.3_

- [ ] 6.2 Add backend validation and error responses
  - Implement source count validation (1-10 sources)
  - Add source existence validation against database
  - Create consistent error response format
  - _Requirements: 1.3, 6.1, 6.2_

- [ ]* 6.3 Write integration tests for error scenarios
  - Test invalid source count submissions
  - Test non-existent source selections
  - Verify authentication and authorization error handling
  - _Requirements: 6.1, 6.2, 6.3_

- [ ] 7. Finalize integration and testing
- [ ] 7.1 Test complete user flow from settings to dashboard
  - Verify source selection saves correctly
  - Test sidebar updates immediately after saving
  - Confirm persistence across browser sessions
  - _Requirements: 1.4, 4.1, 4.2, 5.1, 5.2_

- [ ] 7.2 Implement performance optimizations
  - Add caching for available sources list
  - Optimize database queries for source counts
  - Implement debounced search functionality
  - _Requirements: 3.3, 4.3_

- [ ]* 7.3 Write end-to-end tests for complete feature
  - Test full user journey from source selection to dashboard usage
  - Verify cross-browser compatibility
  - Test responsive design on mobile devices
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 4.1, 4.2_