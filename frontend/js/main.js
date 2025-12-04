// Main application logic and routing

// Initialize the application when DOM is loaded
document.addEventListener('DOMContentLoaded', function() {
    initializeApplication();
});

// Main application initialization
function initializeApplication() {
    // Only perform auth checks for main news page
    const currentPage = window.location.pathname.split('/').pop() || 'index.html';
    
    // Check authentication status only for main news page
    if (currentPage === 'index.html' || currentPage === '') {
        if (!authUtils.isAuthenticated()) {
            // Redirect to login if not authenticated
            window.location.href = 'login.html';
            return;
        }
        
        // Initialize dashboard elements for authenticated users
        initializeDashboard();
    } else if (currentPage === 'login.html' || currentPage === 'register.html') {
        // If user is already authenticated and tries to access login/register, redirect to dashboard
        if (authUtils.isAuthenticated()) {
            window.location.href = 'index.html';
            return;
        }
    }
}

// Initialize dashboard-specific functionality
function initializeDashboard() {
    // Display username in header
    displayUserInfo();


    // Sidebar collapse/toggle removed (UI reverted to static layout)
    
    // Set up periodic news refresh (every 10 minutes to reduce API calls)
    setInterval(function() {
        if (!document.hidden) { // Only refresh if page is visible
            newsUtils.loadNews();
        }
    }, 600000); // 10 minutes (reduced from 5 for better performance) = 300000 ms

    // Right category panel now static vertical; no toggle logic needed.
    
    // Handle browser back/forward buttons
    window.addEventListener('popstate', handleRouteChange);
    
    // Handle visibility change to refresh news when user returns to tab
    document.addEventListener('visibilitychange', function() {
        if (!document.hidden && window.newsUtils && window.newsUtils.loadNews) {
            // Refresh news when user returns to the tab
            setTimeout(() => {
                window.newsUtils.loadNews();
            }, 1000);
        }
    });
    
    // Set up keyboard shortcuts
    setupKeyboardShortcuts();
}

// Display user information in header
function displayUserInfo() {
    const username = authUtils.getUsername();
    const usernameDisplay = document.getElementById('username-display');
    
    if (usernameDisplay && username) {
        usernameDisplay.textContent = username;
    }
}

// Handle route changes (for future expansion)
function handleRouteChange() {
    // Future: Handle different views/routes within the SPA
    console.log('Route changed:', window.location.pathname);
}

// Set up useful keyboard shortcuts
function setupKeyboardShortcuts() {
    document.addEventListener('keydown', function(e) {
        // Don't trigger shortcuts when user is typing in input fields
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
            return;
        }
        
        switch(e.key) {
            case '/':
                // Focus search bar when "/" is pressed
                e.preventDefault();
                const searchInput = document.getElementById('search-input');
                if (searchInput) {
                    searchInput.focus();
                }
                break;
                
            case 'r':
                // Refresh news when "r" is pressed
                if (e.ctrlKey || e.metaKey) {
                    e.preventDefault();
                    newsUtils.loadNews();
                }
                break;
                
            case 'Escape':
                // Close modal when Escape is pressed
                const modal = document.getElementById('bias-analysis-modal');
                if (modal && modal.classList.contains('active')) {
                    closeBiasAnalysisModal();
                }
                break;
        }
    });
}

// Handle online/offline status
function setupNetworkStatusHandling() {
    window.addEventListener('online', function() {
    uiUtils.showToast('Connection restored. Refreshing news...', 'success');
        newsUtils.loadNews();
    });
    
    window.addEventListener('offline', function() {
    uiUtils.showToast('You are offline. Some features may not work.', 'info');
    });
}

// Initialize network status handling
setupNetworkStatusHandling();

// Error handling for uncaught promises
window.addEventListener('unhandledrejection', function(event) {
    console.error('Unhandled promise rejection:', event.reason);
    uiUtils.showToast('An unexpected error occurred. Please refresh the page.', 'error');
});

// Global error handler
window.addEventListener('error', function(event) {
    console.error('Global error:', event.error);
    // Don't show alert for every error to avoid spam
});

// Export main utilities
window.mainUtils = {
    initializeApplication,
    displayUserInfo,
    setupKeyboardShortcuts
};

