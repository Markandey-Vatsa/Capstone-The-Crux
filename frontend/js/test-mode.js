// Test mode for frontend-only testing without backend
// This file provides mock data and functions for testing the UI

// Mock article data
const mockArticles = [
    {
        id: 'mock1',
        title: 'This Everyday Vitamin Could Be The Closest Thing We Have To An "Anti-Aging Pill"',
        summary: 'A study found that daily vitamin D supplements helped slow telomere shortening—the cellular process linked to aging and disease. Researchers believe its anti-inflammatory effects may protect DNA. While promising, more research is needed to confirm these findings.',
        content: 'A groundbreaking study has revealed that a common vitamin supplement might hold the key to slowing down the aging process at the cellular level. The research focused on telomeres, the protective caps at the ends of chromosomes that naturally shorten as we age.',
        source: 'ScienceDaily Health',
        publishDate: new Date(Date.now() - 3600000).toISOString(), // 1 hour ago
        imageUrl: 'https://via.placeholder.com/800x400/1e3a8a/ffffff?text=Science+News',
        url: 'https://example.com/article1',
        tags: ['Health', 'Science', 'Research', 'Aging', 'Vitamins']
    },
    {
        id: 'mock2',
        title: 'Life360 Ventures Into Pet Tech With The Launch Of A New GPS Tracker',
        summary: 'Family location safety app Life360 is venturing into pet tech with the launch of a new tracking device for cats and dogs. The device offers real-time location tracking, activity monitoring, and health insights for pet owners.',
        content: 'Life360, known for its family safety and location-sharing app, is expanding its reach into the pet technology market with the introduction of a comprehensive GPS tracking device designed specifically for cats and dogs.',
        source: 'TechCrunch',
        publishDate: new Date(Date.now() - 7200000).toISOString(), // 2 hours ago
        imageUrl: 'https://via.placeholder.com/800x400/059669/ffffff?text=Tech+News',
        url: 'https://example.com/article2',
        tags: ['Technology', 'Pets', 'GPS', 'Startup', 'Innovation']
    },
    {
        id: 'mock3',
        title: 'One Streaming Quest To Score Electricity In This Social Media Age',
        summary: 'Exploring the intersection of streaming technology and sustainable energy solutions. How content creators are finding innovative ways to power their operations while maintaining their digital presence.',
        content: 'In an era where digital content creation has become a primary source of income for millions, the environmental impact of streaming and content creation is coming under increased scrutiny.',
        source: 'TechCrunch',
        publishDate: new Date(Date.now() - 10800000).toISOString(), // 3 hours ago
        imageUrl: null, // Test placeholder
        url: 'https://example.com/article3',
        tags: ['Streaming', 'Energy', 'Technology', 'Sustainability', 'Environment']
    },
    {
        id: 'mock4',
        title: 'Breaking: Major Breakthrough in Quantum Computing Achieved',
        summary: 'Scientists at leading research institutions have announced a significant advancement in quantum computing technology that could revolutionize data processing and encryption methods.',
        content: 'A consortium of researchers from multiple universities has successfully demonstrated a new quantum computing architecture that maintains coherence for unprecedented durations.',
        source: 'Science Journal',
        publishDate: new Date(Date.now() - 14400000).toISOString(), // 4 hours ago
        imageUrl: 'https://via.placeholder.com/800x400/6366f1/ffffff?text=Quantum+Computing',
        url: 'https://example.com/article4',
        tags: ['Quantum Computing', 'Science', 'Technology', 'Research', 'Innovation']
    },
    {
        id: 'mock5',
        title: 'Global Climate Summit Reaches Historic Agreement on Carbon Reduction',
        summary: 'World leaders have reached a landmark agreement on aggressive carbon reduction targets, with new funding mechanisms for developing nations to transition to clean energy.',
        content: 'After weeks of intense negotiations, representatives from 195 countries have agreed to the most ambitious climate action plan in history.',
        source: 'International News',
        publishDate: new Date(Date.now() - 18000000).toISOString(), // 5 hours ago
        imageUrl: 'https://via.placeholder.com/800x400/10b981/ffffff?text=Climate+News',
        url: 'https://example.com/article5',
        tags: ['Climate', 'Environment', 'Politics', 'International', 'Sustainability']
    }
];

// Mock sources data
const mockSources = [
    { sourceName: 'ScienceDaily Health', articleCount: 45 },
    { sourceName: 'TechCrunch', articleCount: 67 },
    { sourceName: 'Science Journal', articleCount: 23 },
    { sourceName: 'International News', articleCount: 89 },
    { sourceName: 'Business Weekly', articleCount: 34 },
    { sourceName: 'Tech Today', articleCount: 56 },
    { sourceName: 'Health Monitor', articleCount: 28 }
];

// Test mode flag
let isTestMode = false;

// Enable test mode
function enableTestMode() {
    isTestMode = true;
    console.log('Test mode enabled - using mock data');
    
    // Override the auth utils for test mode
    window.authUtils = window.authUtils || {};
    window.authUtils.makeAuthenticatedRequest = mockAuthenticatedRequest;
    
    // Load mock data
    if (window.newsUtils) {
        window.newsUtils.setCurrentArticles(mockArticles);
        if (window.renderingUtils) {
            window.renderingUtils.renderArticles(mockArticles);
        }
    }
    
    // Load mock sources
    if (window.newsUtils && window.newsUtils.renderSourcesSidebar) {
        window.newsUtils.renderSourcesSidebar(mockSources);
    }
}

// Mock authenticated request function
async function mockAuthenticatedRequest(url) {
    console.log('Mock request to:', url);
    
    // Simulate network delay
    await new Promise(resolve => setTimeout(resolve, 500));
    
    // Parse URL to determine what to return
    if (url.includes('/news/sources-with-counts')) {
        return {
            ok: true,
            status: 200,
            json: async () => mockSources
        };
    } else if (url.includes('/news/source/')) {
        // Extract source name from URL
        const sourceName = decodeURIComponent(url.split('/news/source/')[1].split('?')[0]);
        const filteredArticles = mockArticles.filter(article => article.source === sourceName);
        return {
            ok: true,
            status: 200,
            json: async () => ({
                content: filteredArticles,
                totalPages: 1,
                number: 0,
                size: 20,
                numberOfElements: filteredArticles.length,
                totalElements: filteredArticles.length,
                last: true
            })
        };
    } else if (url.includes('/news/tag/')) {
        // Extract tag from URL
        const tag = decodeURIComponent(url.split('/news/tag/')[1].split('?')[0]);
        const filteredArticles = mockArticles.filter(article => 
            article.tags && article.tags.some(t => t.toLowerCase().includes(tag.toLowerCase()))
        );
        return {
            ok: true,
            status: 200,
            json: async () => ({
                content: filteredArticles,
                totalPages: 1,
                number: 0,
                size: 20,
                numberOfElements: filteredArticles.length,
                totalElements: filteredArticles.length,
                last: true
            })
        };
    } else if (url.includes('/news/search')) {
        // Extract search query
        const urlParams = new URLSearchParams(url.split('?')[1]);
        const query = urlParams.get('q');
        const filteredArticles = mockArticles.filter(article => 
            article.title.toLowerCase().includes(query.toLowerCase()) ||
            article.summary.toLowerCase().includes(query.toLowerCase())
        );
        return {
            ok: true,
            status: 200,
            json: async () => ({
                content: filteredArticles,
                totalPages: 1,
                number: 0,
                size: 20,
                numberOfElements: filteredArticles.length,
                totalElements: filteredArticles.length,
                last: true
            })
        };
    } else if (url.includes('/news')) {
        // Default news endpoint
        return {
            ok: true,
            status: 200,
            json: async () => ({
                content: mockArticles,
                totalPages: 1,
                number: 0,
                size: 20,
                numberOfElements: mockArticles.length,
                totalElements: mockArticles.length,
                last: true
            })
        };
    } else if (url.includes('/user/profile')) {
        return {
            ok: true,
            status: 200,
            json: async () => ({
                interests: ['Technology', 'Science', 'Health'],
                profession: 'Software Developer'
            })
        };
    }
    
    // Default response for unknown endpoints
    return {
        ok: false,
        status: 404,
        json: async () => ({ error: 'Not found' })
    };
}

// Auto-enable test mode if backend is not available
function checkBackendAndEnableTestMode() {
    // Try to make a simple request to check if backend is available
    fetch(`${window.API_BASE_URL}/health`, { method: 'GET' })
        .then(response => {
            if (!response.ok) {
                console.log('Backend health check failed, enabling test mode');
                enableTestMode();
            }
        })
        .catch(error => {
            console.log('Backend not available, enabling test mode');
            enableTestMode();
        });
}

// Export functions
window.testMode = {
    enable: enableTestMode,
    checkBackendAndEnableTestMode,
    mockArticles,
    mockSources
};

// Auto-check backend availability when this script loads
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', checkBackendAndEnableTestMode);
} else {
    checkBackendAndEnableTestMode();
}