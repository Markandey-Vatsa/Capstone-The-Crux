// News fetching, search, bias analysis, and text-to-speech functionality

// Application state
let currentPage = 0;
// Removed single-article index state (legacy)
let currentCategory = 'All News';
let currentSource = null;
let currentSearchQuery = '';
// bias signal filter removed
let totalPages = 0;
let currentArticles = []; // Accumulated articles for grid view
let isLoading = false;
let allSources = []; // Store all sources for filtering
// Track how many articles per source have been rendered this session (UI cap at 50)
let renderedSourceCounts = {};

// Hybrid infinite scroll state
let articlesLoadedOnPage = 0;
const ARTICLES_PER_PAGE = 25; // threshold before switching to manual load

// Text-to-Speech state
let currentSpeech = null;
let isSpeaking = false;

// Infinite scroll state
let isLoadingMore = false;
let hasMorePages = true;
let infiniteScrollObserver = null;

// Cap the number of article cards kept in the DOM to prevent memory bloat
const MAX_ARTICLES_IN_DOM = 30; // Reduced from 50 for better performance

// Provide a fetchNews alias expected by spec (calls loadMoreArticles when scrolling, or initial loadNews)
async function fetchNews(next = false) {
    if (next) {
        return loadMoreArticles();
    }
    return loadNews();
}

// Export functions for global access - define early
window.newsUtils = window.newsUtils || {};

// New initialization ensuring first network action: authorized profile fetch
document.addEventListener('DOMContentLoaded', () => {
    initNewsApp();
});

async function initNewsApp() {
    const container = document.getElementById('category-tabs');
    let interests = [];
    let profession = null;
    // 1. Fetch profile FIRST (authorized) before any other logic
    try {
           const resp = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/user/profile`);
        if (resp && resp.ok) {
            const profile = await resp.json();
            interests = Array.isArray(profile.interests) ? profile.interests.filter(v => v && v.trim().length) : [];
            profession = profile.profession && profile.profession.trim().length ? profile.profession.trim() : null;
        }
    } catch (e) {
        console.warn('Profile fetch failed or user unauthenticated; proceeding with public view.', e);
    }

    // 2. Build category tabs based on interests (For You + All News + each interest)
    if (container) {
        container.innerHTML = '';
        const tabs = [];
    if (interests.length > 0 || profession) tabs.push('For You');
        tabs.push('All News');
        tabs.push(...interests);
        tabs.forEach(label => {
            const tab = document.createElement('div');
            tab.className = 'category-tab' + (label === 'All News' ? ' active' : '');
            tab.dataset.category = label;
            tab.textContent = label;
            container.appendChild(tab);
        });
        if (!container._delegatedListenerAdded) {
            container.addEventListener('click', (e) => {
                const target = e.target.closest('.category-tab');
                if (!target) return;
                const cat = target.dataset.category;
                console.log('[CategoryTabs] Click ->', cat);
                handleCategoryChange(cat);
            });
            container._delegatedListenerAdded = true;
        }
    }

    // 3. Now initialize other interface features
    initializeNewsInterface();
    setupVoicePreference();
    
    // 4. Load sources and news in parallel for better performance
    Promise.all([
        loadSourcesWithCounts(),
        loadNews()
    ]).catch(error => {
        console.error('Error during parallel initialization:', error);
    });
}

// Initialize all news interface event listeners
function initializeNewsInterface() {
    // Search functionality
    const searchBtn = document.getElementById('search-btn');
    const searchInput = document.getElementById('search-input');
    const voiceSearchBtn = document.getElementById('voice-search-btn');
    
    searchBtn.addEventListener('click', handleSearch);
    searchInput.addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            handleSearch();
        }
    });
    voiceSearchBtn.addEventListener('click', startVoiceSearch);
    
    // Sidebar source search functionality
    const sourceSearchInput = document.getElementById('source-search');
    if (sourceSearchInput) {
        sourceSearchInput.addEventListener('input', filterSources);
    }

    // bias signal filter removed
    
    // Category tabs are built dynamically after profile fetch; listeners added there
    
    // Infinite scroll setup
    setupInfiniteScroll();
    
    // Removed arrow key navigation & single-article specific handlers
    
    // Modal controls
    const modalCloseBtn = document.getElementById('modal-close-btn');
    const modal = document.getElementById('bias-analysis-modal');
    
    modalCloseBtn.addEventListener('click', closeBiasAnalysisModal);
    modal.addEventListener('click', function(e) {
        if (e.target === modal) {
            closeBiasAnalysisModal();
        }
    });
}

// Setup infinite scroll functionality
function setupInfiniteScroll() {
    const trigger = document.getElementById('infinite-scroll-trigger');
    if (!trigger) return;
    const loadMoreBtn = document.getElementById('load-more-btn');
    if (loadMoreBtn && !loadMoreBtn._listenerAdded) {
        loadMoreBtn.addEventListener('click', () => {
            articlesLoadedOnPage = 0; // reset batch counter
            loadMoreBtn.classList.add('hidden');
            trigger.classList.remove('hidden');
            fetchNews(true); // immediately load next batch
        });
        loadMoreBtn._listenerAdded = true;
    }
    
    // Create intersection observer
    infiniteScrollObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting && !isLoadingMore && hasMorePages) {
                // Use fetchNews alias for clarity; pass true to indicate next page
                fetchNews(true);
            }
        });
    }, {
        rootMargin: '100px' // Trigger 100px before the element is visible
    });
    
    infiniteScrollObserver.observe(trigger);
}

// Load more articles for infinite scroll
async function loadMoreArticles() {
    if (isLoadingMore || !hasMorePages) return;
    
    isLoadingMore = true;
        showInfiniteScrollLoading(true);
    
    try {
        const nextPage = currentPage + 1;
        let url;

        if (currentSearchQuery) {
            const params = [
                `q=${encodeURIComponent(currentSearchQuery)}`,
                `page=${nextPage}`,
                'size=20'
            ];
            url = `${window.API_BASE_URL}/news/search?${params.join('&')}`;
        } else if (currentCategory === 'For You') {
            url = `${window.API_BASE_URL}/news/for-you?page=${nextPage}&size=20`;
            if (currentSource) {
                const sourceKey = resolveSourceKeyForFetch(currentSource);
                if (sourceKey) url += `&source=${encodeURIComponent(sourceKey)}`;
            }
        } else {
            const params = [`page=${nextPage}`, 'size=20'];
            if (currentCategory && currentCategory !== 'All News') {
                params.push(`category=${encodeURIComponent(currentCategory)}`);
            }
            if (currentSource) {
                const sourceKey = resolveSourceKeyForFetch(currentSource);
                if (sourceKey) params.push(`source=${encodeURIComponent(sourceKey)}`);
            }
            url = `${window.API_BASE_URL}/news?${params.join('&')}`;
        }

        const response = await authUtils.makeAuthenticatedRequest(url);
        const data = await response.json();

        if (data.content && data.content.length > 0) {
            // Filter incoming batch to respect per-source 50 cap
            const filteredBatch = [];
            data.content.forEach(article => {
                const src = article.source || 'Unknown';
                const currentCount = renderedSourceCounts[src] || 0;
                if (currentCount < 50) {
                    filteredBatch.push(article);
                    renderedSourceCounts[src] = currentCount + 1;
                }
            });

            if (filteredBatch.length > 0) {
                currentArticles = currentArticles.concat(filteredBatch);
                // Trim in-memory list to match DOM cap (keep most recent at the end)
                if (currentArticles.length > MAX_ARTICLES_IN_DOM) {
                    currentArticles = currentArticles.slice(-MAX_ARTICLES_IN_DOM);
                }
                currentPage = nextPage;
            }
            // Determine if more pages should be fetched: either backend last page OR all sources in this batch were exhausted (still allow next page in case other sources have more)
            hasMorePages = !data.last;

            // If user filtered by single source and reached 50, stop pagination regardless of backend
            if (currentSource) {
                const sourceKey = resolveSourceKeyForFetch(currentSource);
                if (sourceKey && (renderedSourceCounts[sourceKey] || 0) >= 50) {
                hasMorePages = false;
                }
            }

            renderArticlesGrid(filteredBatch, true);
            // Enforce DOM cap after appending new batch
            capArticlesDom();

            // Increment counter with actually rendered articles & evaluate hybrid switch
            articlesLoadedOnPage += filteredBatch.length;
            evaluateHybridInfiniteScroll();

            // If nothing from this page could be displayed due to caps yet hasMorePages still true, auto fetch next page to try fill remaining UI (avoid user seeing stall)
            if (filteredBatch.length === 0 && hasMorePages) {
                // Recursive call guarded by isLoadingMore flag after current turn finishes
                setTimeout(() => loadMoreArticles(), 0);
            }
        } else {
            hasMorePages = false;
        }
        
    } catch (error) {
        console.error('Error loading more articles:', error);
            uiUtils.showToast('Failed to load more articles.', 'error');
    } finally {
        isLoadingMore = false;
        showInfiniteScrollLoading(false);
    }
}

// Show/hide infinite scroll loading indicator
function showInfiniteScrollLoading(show) {
    const loadingElement = document.getElementById('infinite-scroll-loading');
    if (loadingElement) {
        if (show) {
            loadingElement.classList.remove('hidden');
        } else {
            loadingElement.classList.add('hidden');
        }
    }
}

// Load news articles from API
async function loadNews() {
    if (isLoading) return;
    // Ensure viewport resets to top on new fetch (filter/source/category change)
    try { window.scrollTo(0, 0); } catch(_) {}
    
    isLoading = true;
    showLoadingIndicator(true);
    uiUtils.setGlobalSpinner(true);
    
    // Reset infinite scroll state for new searches/filters
    currentPage = 0;
    // Reset article accumulation and hybrid counters
    articlesLoadedOnPage = 0;
    hasMorePages = true;
    isLoadingMore = false;
    // Reset per-source rendered counts
    renderedSourceCounts = {};
    
    try {
        // If loading For You or All News first time, attempt briefing (only once per page load)
        if (!window._briefingLoadedOnce) {
            fetchDailyBriefing();
            window._briefingLoadedOnce = true;
        }

        // Build API URL (category param first for clarity) with proper encoding & logging
        let apiUrl;
        if (currentSearchQuery) {
            const params = [
                `q=${encodeURIComponent(currentSearchQuery)}`,
                `page=${currentPage}`,
                'size=20'
            ];
            apiUrl = `${window.API_BASE_URL}/news/search?${params.join('&')}`;
        } else if (currentCategory === 'For You') {
            apiUrl = `${window.API_BASE_URL}/news/for-you?page=${currentPage}&size=20`;
            if (currentSource) {
                const sourceKey = resolveSourceKeyForFetch(currentSource);
                if (sourceKey) apiUrl += `&source=${encodeURIComponent(sourceKey)}`;
            }
        } else {
            const params = [];
            if (currentCategory && currentCategory !== 'All News') params.push(`category=${encodeURIComponent(currentCategory)}`);
            params.push(`page=${currentPage}`);
            params.push('size=20');
            if (currentSource) {
                const sourceKey = resolveSourceKeyForFetch(currentSource);
                if (sourceKey) params.push(`source=${encodeURIComponent(sourceKey)}`);
            }
            apiUrl = `${window.API_BASE_URL}/news?${params.join('&')}`;
        }
        console.log('[loadNews] Fetch URL:', apiUrl);
        
        // Make authenticated request to fetch news
        const response = await authUtils.makeAuthenticatedRequest(apiUrl);
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const newsData = await response.json();
        
    // Update pagination state
        totalPages = newsData.totalPages;
        const incoming = newsData.content || [];
        const filteredInitial = [];
        incoming.forEach(article => {
            const src = article.source || 'Unknown';
            const currentCount = renderedSourceCounts[src] || 0;
            if (currentCount < 50) {
                filteredInitial.push(article);
                renderedSourceCounts[src] = currentCount + 1;
            }
        });
        currentArticles = filteredInitial;

        // If single source selected and we already hit 50, stop further loads
        if (currentSource && (renderedSourceCounts[currentSource] || 0) >= 50) {
            hasMorePages = false;
        }

    renderArticlesGrid(currentArticles, false);
    // Ensure initial render also respects DOM cap (safety)
    capArticlesDom();

        // If For You selected and no content, show helpful message
        if (currentCategory === 'For You' && currentArticles.length === 0) {
            const container = document.getElementById('articles-container');
            if (container) {
                container.innerHTML = `
                    <div class="for-you-empty glass-card">
                        <h3 style="margin-top:0;">Your 'For You' feed is empty</h3>
                        <p>We don't have personalized articles yet. Visit your <a href="profile.html">Profile</a> and select your favorite news topics to build a tailored feed.</p>
                        <p style="margin:0; font-size:0.85rem; opacity:.75;">After saving preferences, return here and click the 'For You' tab.</p>
                    </div>`;
            }
        }

        // Update counter with rendered articles
        articlesLoadedOnPage += currentArticles.length;
    evaluateHybridInfiniteScroll();
        
    } catch (error) {
        console.error('Error loading news:', error);
    uiUtils.showToast('Failed to load news articles. Please try again.', 'error');
        document.getElementById('articles-container').innerHTML = 
            '<p class="text-center">Failed to load articles. Please try again later.</p>';
    } finally {
        isLoading = false;
        showLoadingIndicator(false);
    uiUtils.setGlobalSpinner(false);
    }
}

// Fetch structured daily briefing bullet points
async function fetchDailyBriefing() {
    const container = document.getElementById('daily-briefing-container');
    if (!container) return;
    container.style.display = 'none';
    container.innerHTML = '<div class="briefing-box"><p class="briefing-intro">Preparing your personalized briefing...</p></div>';
    try {
        const resp = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/briefing`);
        if (!resp || !resp.ok) return;
        const data = await resp.json();
        if (data && Array.isArray(data.briefing_points) && data.briefing_points.length) {
            renderPersonalizedBriefing(container, data.briefing_points);
            container.style.display = 'block';
            const refreshBtn = document.getElementById('refresh-briefing-btn');
            if (refreshBtn) refreshBtn.style.display = 'inline-flex';
        }
    } catch (e) {
        console.warn('Briefing fetch failed', e);
    }
}

// Build & inject personalized structured briefing component
function renderPersonalizedBriefing(container, pointsArray) {
    const userName = (authUtils.getUsername && authUtils.getUsername()) || 'there';
    const greeting = getPersonalizedGreeting(userName);
    const cleanedPoints = (pointsArray || []).slice(0,7).map(p => {
        if (!p) return null;
        let s = String(p).trim().replace(/\s+/g,' ');
        // Strip leading bullets or numbering
        s = s.replace(/^\s*([\-*•\d]+[.)]?\s*)/, '');
        // Ensure ends with punctuation
        if (!/[.!?]$/.test(s)) s += '.';
        return s;
    }).filter(Boolean);
    if (!cleanedPoints.length) {
        container.style.display = 'none';
        return;
    }
    const listItems = cleanedPoints.map((s) => `<li><span class="bp-text">${sanitizeHtml(s)}</span></li>`).join('');
    container.innerHTML = `
        <div class="briefing-box enhanced">
            <div class="briefing-header">
                <div class="briefing-badge">Today's Top Stories</div>
                <div class="briefing-time">${new Date().toLocaleDateString(undefined,{weekday:'short', month:'short', day:'numeric'})}</div>
            </div>
            <h2 id="greeting-heading">${sanitizeHtml(greeting)}</h2>
            <ul id="summary-points" class="briefing-points">${listItems}</ul>
        </div>`;
}

// Manual refresh handler
document.addEventListener('click', async (e) => {
    if (e.target.closest('#refresh-briefing-btn')) {
        const btn = document.getElementById('refresh-briefing-btn');
        if (!btn) return;
        btn.disabled = true;
        const origHtml = btn.innerHTML;
        btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';
        try {
            const resp = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/briefing/refresh`, { method: 'POST' });
            if (resp && resp.ok) {
                const data = await resp.json();
                const container = document.getElementById('daily-briefing-container');
                if (container && Array.isArray(data.briefing_points)) {
                    renderPersonalizedBriefing(container, data.briefing_points);
                }
            }
        } catch(err) {
            console.warn('Briefing refresh failed', err);
        } finally {
            btn.disabled = false;
            btn.innerHTML = origHtml;
        }
    }
});

function getPersonalizedGreeting(userName) {
    const hour = new Date().getHours();
    if (hour < 12) return `Good morning, ${userName}`;
    if (hour < 18) return `Good afternoon, ${userName}`;
    return `Good evening, ${userName}`;
}


// Render articles in a responsive grid
function renderArticlesGrid(batch, append = false) {
    const container = document.getElementById('articles-container');
    if (!container) return;

    if (!append) {
        container.innerHTML = '';
    }

    if (!batch || batch.length === 0) {
        if (!append) {
            container.innerHTML = '<p class="text-center">No articles found.</p>';
        }
        return;
    }

    // Batch DOM insert to reduce reflows
    const frag = document.createDocumentFragment();
    batch.forEach(article => {
        const wrapper = document.createElement('div');
        wrapper.innerHTML = createArticleCard(article);
        const card = wrapper.firstElementChild;
        frag.appendChild(card);
        // Initialize bookmark status asynchronously
        initializeBookmarkStatus(article.id);
        // Finalize clamp + read more visibility
        finalizeCardUI(card);
    });
    container.appendChild(frag);

    // Apply DOM capping universally after rendering
    capArticlesDom();
}

// Remove oldest article cards from the top until we stay within the cap
function capArticlesDom() {
    const container = document.getElementById('articles-container');
    if (!container) return;
    // Use children (only article cards are appended here)
    while (container.children.length > MAX_ARTICLES_IN_DOM) {
        // Remove the oldest (top-most) card
        container.removeChild(container.firstElementChild);
    }
}

// Create article card HTML (grid-friendly)
// Max number of tag pills shown beneath each article to reduce visual clutter
const MAX_ARTICLE_TAGS = 4;

// Inject a ripple span into buttons when clicked
function attachGlobalButtonRipple() {
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.btn');
        if (!btn) return;
        const rect = btn.getBoundingClientRect();
        const ripple = document.createElement('span');
        ripple.className = 'ripple';
        const size = Math.max(rect.width, rect.height);
        ripple.style.width = ripple.style.height = size + 'px';
        ripple.style.left = (e.clientX - rect.left - size/2) + 'px';
        ripple.style.top = (e.clientY - rect.top - size/2) + 'px';
        btn.appendChild(ripple);
        setTimeout(() => ripple.remove(), 600);
    });
}
attachGlobalButtonRipple();

function createArticleCard(article) {
    const publishDate = new Date(article.publishDate).toLocaleDateString();
    const isGuardianFull = (article.source || '').includes('The Guardian (Full Article)');
    // Normalize source label for display
    let displaySource = article.source || '';
    if (displaySource === 'The Guardian (Full Article)') {
        displaySource = 'The Guardian';
    }
    const rawOrSummary = article.content || article.summary || '';
    // For Guardian full articles we initially render ONLY a plain-text preview so CSS clamp works.
    // We stash the sanitized full HTML in a data attribute to inject on first expansion.
    let guardianPreview = '';
    let guardianFullHtml = '';
    if (isGuardianFull) {
        guardianFullHtml = safeArticleHtml(rawOrSummary || '');
        guardianPreview = stripHtml(guardianFullHtml).slice(0, 2000); // hard cap preview text length
    }
    const fullContent = isGuardianFull ? guardianPreview : stripHtml(rawOrSummary);
    
    // Robust image URL extraction
    let imageUrl = extractImageFromArticle(article);
    
    // Create image HTML with proper fallback handling
    let imageHtml = '';
    if (imageUrl) {
        imageHtml = `
            <div class="article-media">
                <img src="${sanitizeHtml(imageUrl)}" 
                     alt="${sanitizeHtml(article.title)}" 
                     class="article-image" 
                     onload="classifyOrientation(this)"
                     onerror="handleImageError(this, '${sanitizeHtml(displaySource)}', '${sanitizeHtml(article.category)}')">
                <div class="article-image-placeholder" style="display: none;">
                    <span>📰 ${sanitizeHtml(displaySource)}</span>
                </div>
            </div>`;
    } else {
        imageHtml = `<div class="article-media">${createPlaceholderImage(displaySource, article.category)}</div>`;
    }
    
    // Build tag pills (if tags array present)
    let tagsHtml = '';
    if (article.tags && Array.isArray(article.tags) && article.tags.length) {
        const limited = article.tags.slice(0, MAX_ARTICLE_TAGS); // capped for clarity
        const remaining = article.tags.length - limited.length;
        const pills = limited.map(t => `<span class="tag-pill" title="${sanitizeHtml(t)}">${sanitizeHtml(t)}</span>`).join('');
        const more = remaining > 0 ? `<span class="tag-pill more-tags" data-full='${JSON.stringify(article.tags.map(sanitizeHtml))}'>+${remaining}</span>` : '';
        tagsHtml = `<div class="article-tags">${pills}${more}</div>`;
    }

    // Bias signal indicator removed

    return `
        <div class="article-card glass-card">
            ${imageHtml}
            <div class="article-content-wrapper">
                <h3 class="article-title">${sanitizeHtml(article.title)}</h3>
                <div class="article-meta">
                    <span class="article-source">${sanitizeHtml(displaySource)}</span>
                    <span class="meta-right"><span class="article-date">${publishDate}</span></span>
                </div>
                <p class="article-summary${isGuardianFull ? ' guardian-full' : ''}" aria-expanded="false"${isGuardianFull ? ` data-full-html="${encodeURIComponent(guardianFullHtml)}" data-preview-text="${encodeURIComponent(guardianPreview)}" data-html-loaded="false"` : ''}>${isGuardianFull ? sanitizeHtml(fullContent) : sanitizeHtml(fullContent)}</p>
                <button class="read-more-btn" type="button">Read More</button>
                ${tagsHtml}
                <div class="article-actions">
                    <button class="btn btn-primary btn-small glass-card analyze-bias-btn" data-id="${article.id}" onclick="analyzeBias('${article.id}')"><span class="btn-label">Analyze Bias</span></button>
                    <button id="bookmark-btn-${article.id}" class="btn btn-secondary btn-small glass-card bookmark-btn" onclick="toggleBookmark('${article.id}')">
                        <i class="far fa-bookmark"></i>
                        <span class="btn-text">Save</span>
                    </button>
                    <button class="btn btn-secondary btn-small glass-card reading-list-btn" onclick="showReadingListDropdown('${article.id}')">
                        <i class="fas fa-list"></i>
                        <span class="btn-text">Add to List</span>
                    </button>
                    <button class="btn btn-secondary btn-small glass-card export-btn" onclick="exportArticle('${article.id}')">
                        <i class="fas fa-download"></i>
                        <span class="btn-text">Export</span>
                    </button>
                    <button id="chat-btn-${article.id}" class="btn btn-secondary btn-small glass-card chat-btn" onclick="openChatModal('${article.id}')">
                        <i class="fas fa-robot"></i>
                        <span class="btn-text">Talk to Chat Bot</span>
                    </button>
                    <button id="listen-btn-${article.id}" class="btn btn-secondary btn-small glass-card" onclick="readArticle('${article.id}')">Listen</button>
                    <a href="${article.url}" target="_blank" class="btn btn-secondary btn-small glass-card">Read Full</a>
                </div>
                <div id="tts-controls-${article.id}" class="tts-controls hidden glass-card">
                    <button class="tts-btn glass-card" onclick="pauseResumeArticle('${article.id}')">Pause</button>
                    <button class="tts-btn glass-card" onclick="stopReadingArticle('${article.id}')">Stop</button>
                </div>
            </div>
        </div>`;
}

// Robust image extraction from article data
function extractImageFromArticle(article) {
    // Method 1: Check dedicated imageUrl field from backend
    if (article.imageUrl && article.imageUrl.trim() !== '') {
        let imageUrl = article.imageUrl.trim();
        
        // Handle protocol-relative URLs
        if (imageUrl.startsWith('//')) {
            imageUrl = 'https:' + imageUrl;
        }
        
        // Validate the URL
        if (isValidImageUrl(imageUrl)) {
            console.log('✅ Using imageUrl from backend:', imageUrl);
            return imageUrl;
        }
    }
    
    // Method 2: Parse content/description for img tags
    const contentSources = [article.content, article.summary, article.description];
    
    for (let content of contentSources) {
        if (content && typeof content === 'string') {
            const imageUrl = extractImageFromHTML(content);
            if (imageUrl) {
                console.log('✅ Extracted image from content:', imageUrl);
                return imageUrl;
            }
        }
    }
    
    // Method 3: Try to find images in any other text fields
    for (let key in article) {
        if (typeof article[key] === 'string' && article[key].includes('<img')) {
            const imageUrl = extractImageFromHTML(article[key]);
            if (imageUrl) {
                console.log('✅ Extracted image from field', key + ':', imageUrl);
                return imageUrl;
            }
        }
    }
    
    console.log('❌ No image found for article:', article.title);
    return null;
}

// Extract image URL from HTML content
function extractImageFromHTML(htmlContent) {
    if (!htmlContent) return null;
    
    // Multiple regex patterns to catch different img tag formats
    const imgPatterns = [
        /<img[^>]+src=['"]([^'"]+)['"][^>]*>/gi,
        /<img[^>]+src=([^>\s]+)[^>]*>/gi,
        /src=['"]([^'"]*\.(jpg|jpeg|png|gif|webp|bmp)[^'"]*)['"]/gi,
        /data-src=['"]([^'"]*\.(jpg|jpeg|png|gif|webp|bmp)[^'"]*)['"]/gi
    ];
    
    for (let pattern of imgPatterns) {
        const matches = htmlContent.matchAll(pattern);
        for (let match of matches) {
            let imageUrl = match[1];
            
            // Clean up the URL
            if (imageUrl) {
                imageUrl = imageUrl.trim().replace(/&amp;/g, '&');
                
                // Handle protocol-relative URLs
                if (imageUrl.startsWith('//')) {
                    imageUrl = 'https:' + imageUrl;
                }
                
                // Skip relative URLs that don't start with http
                if (!imageUrl.startsWith('http')) {
                    continue;
                }
                
                if (isValidImageUrl(imageUrl)) {
                    return imageUrl;
                }
            }
        }
    }
    
    return null;
}

// Validate image URL
function isValidImageUrl(url) {
    if (!url || typeof url !== 'string') return false;
    
    // Must be a proper URL
    try {
        new URL(url);
    } catch {
        return false;
    }
    
    // Must have image extension or be from known image hosting services
    const imageExtensions = /\.(jpg|jpeg|png|gif|webp|bmp|svg)(\?.*)?$/i;
    const imageHosts = /\.(imgur|flickr|unsplash|pexels|pixabay|cloudinary|amazonaws)\.com/i;
    
    if (!imageExtensions.test(url) && !imageHosts.test(url)) {
        return false;
    }
    
    // Avoid placeholder or loading images
    const badPatterns = /(placeholder|loading|spinner|blank|default|noimage|transparent)/i;
    if (badPatterns.test(url)) {
        return false;
    }
    
    // URL should be reasonable length
    if (url.length < 10 || url.length > 2000) {
        return false;
    }
    
    return true;
}

// Create a themed placeholder image based on source and category
function createPlaceholderImage(source, category) {
    const categoryEmojis = {
        'General': '📰',
        'Business': '💼',
        'Technology': '💻',
        'Sports': '⚽',
        'International': '🌍'
    };
    
    const categoryColors = {
        'General': 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
        'Business': 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)',
        'Technology': 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)',
        'Sports': 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)',
        'International': 'linear-gradient(135deg, #fa709a 0%, #fee140 100%)'
    };
    
    const emoji = categoryEmojis[category] || '📰';
    const gradient = categoryColors[category] || categoryColors['General'];
    
    return `
        <div class="article-image-placeholder" style="background: ${gradient};">
            <span>${emoji} ${sanitizeHtml(source)}</span>
        </div>`;
}

// Handle image loading errors
function handleImageError(imgElement, source, category) {
    // Hide the broken image
    imgElement.style.display = 'none';
    
    // Show the placeholder
    const placeholder = imgElement.nextElementSibling;
    if (placeholder && placeholder.classList.contains('article-image-placeholder')) {
        placeholder.style.display = 'flex';
        
        // Update placeholder content with better styling
        const categoryEmojis = {
            'General': '📰',
            'Business': '💼', 
            'Technology': '💻',
            'Sports': '⚽',
            'International': '🌍'
        };
        
        const emoji = categoryEmojis[category] || '📰';
        placeholder.innerHTML = `<span>${emoji} ${source}</span>`;
    }
}

// Classify orientation to adjust portrait images
function classifyOrientation(img){
    try {
        if(img.naturalHeight > img.naturalWidth * 1.2){
            img.classList.add('portrait');
        }
    } catch(_) {}
}

// Handle search functionality
function handleSearch() {
    const searchInput = document.getElementById('search-input');
    const query = searchInput.value.trim();
    
    currentSearchQuery = query;
    currentPage = 0; // Reset to first page
    // Global search should run without any category or source filters
    currentCategory = 'All News';
    currentSource = null;
    // Removed single-article index reset
    
    // Update UI to show search state
    if (query) {
        // Remove active class from category tabs during search
        document.querySelectorAll('.category-tab').forEach(tab => tab.classList.remove('active'));
        const allNewsTab = Array.from(document.querySelectorAll('.category-tab'))
            .find(tab => tab.dataset.category === 'All News');
        if (allNewsTab) allNewsTab.classList.add('active');
        // Clear highlighted sources so the user sees search is universal
        document.querySelectorAll('.source-item').forEach(item => item.classList.remove('active'));
    }
    
    loadNews();
}

// Handle category changes
function handleCategoryChange(category) {
    console.log('[handleCategoryChange] category selected:', category);
    currentCategory = category;
    currentPage = 0; // Reset to first page
    // Removed single-article index reset
    currentSearchQuery = ''; // Clear search
    
    // Clear search input
    document.getElementById('search-input').value = '';
    
    // Update active tab
    document.querySelectorAll('.category-tab').forEach(tab => {
        tab.classList.remove('active');
        if (tab.dataset.category === category) {
            tab.classList.add('active');
        }
    });
    
    loadNews();
}

// Expand tag overflow on click
document.addEventListener('click', (e) => {
    const more = e.target.closest('.more-tags');
    if (more) {
        try {
            const full = JSON.parse(more.dataset.full || '[]');
            const parent = more.parentElement;
            if (parent) {
                parent.innerHTML = full.map(t => `<span class="tag-pill" title="${t}">${t}</span>`).join('');
            }
        } catch(_) {}
    }
});

// Analyze Bias button mutation helpers
function setAnalyzeButtonLoading(articleId, loading) {
    const btn = document.querySelector(`.analyze-bias-btn[data-id="${articleId}"]`);
    if (!btn) return;
    if (loading) {
        btn.classList.add('is-loading');
        btn.dataset.originalLabel = btn.dataset.originalLabel || btn.textContent.trim();
        const labelEl = btn.querySelector('.btn-label');
        if (labelEl) labelEl.textContent = 'Analyzing…';
    } else {
        btn.classList.remove('is-loading');
        const labelEl = btn.querySelector('.btn-label');
        if (labelEl && btn.dataset.originalLabel) labelEl.textContent = btn.dataset.originalLabel;
    }
}

// bias filter UI removed

// Cache for sources to avoid repeated API calls
let sourcesCache = null;
let sourcesCacheTime = 0;
const SOURCES_CACHE_TTL = 5 * 60 * 1000; // 5 minutes

// Load sources with article counts from API
async function loadSourcesWithCounts() {
    try {
        // Check cache first
        const now = Date.now();
        if (sourcesCache && (now - sourcesCacheTime) < SOURCES_CACHE_TTL) {
            console.log('Using cached sources data');
            displaySources(sourcesCache);
            return;
        }
        
        console.log('Fetching sources from API...');
        const startTime = performance.now();
        
        // Show loading state
        const sourcesList = document.getElementById('sources-list');
        if (sourcesList) {
            sourcesList.innerHTML = '<li class="source-loading">Loading sources...</li>';
        }
        
        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/sources-with-counts`);
        
        if (!response.ok) {
            throw new Error(`Failed to load sources: ${response.status}`);
        }
        
        const sources = await response.json();
        const endTime = performance.now();
        
        console.log(`Sources loaded in ${Math.round(endTime - startTime)}ms`);
        
        // Update cache
        sourcesCache = sources;
        sourcesCacheTime = now;
        allSources = sources;
        
        displaySources(sources);
        
    } catch (error) {
        console.error('Error loading sources:', error);
    uiUtils.showToast('Failed to load news sources.', 'error');
    }
}

// Display sources in the sidebar
function displaySources(sources) {
    const sourcesList = document.getElementById('sources-list');
    
    if (!sourcesList) {
        console.warn('Sources list element not found');
        return;
    }
    
    // Use DocumentFragment for efficient DOM manipulation
    const fragment = document.createDocumentFragment();

    // Load custom order if present
    const storedOrder = JSON.parse(localStorage.getItem('user_source_order') || '[]');
    const sourceMap = new Map(sources.map(s => [s.sourceName, s]));
    let ordered = [];
    if (storedOrder.length) {
        storedOrder.forEach(name => { if (sourceMap.has(name)) ordered.push(sourceMap.get(name)); });
        // Append any new sources not yet ordered
        sources.forEach(s => { if (!storedOrder.includes(s.sourceName)) ordered.push(s); });
    } else {
        ordered = sources;
    }

    ordered.forEach(source => {
        // Filter out deprecated Guardian RSS feed if it somehow appears
        if (source.sourceName === 'The Guardian World' ||
            source.sourceName === 'The Guardian (RSS Feed)') {
            return;
        }
        const listItem = document.createElement('li');
        listItem.className = 'source-item';
        // Backend key used for API filtering
        const backendKey = source.sourceName;
        // Display label cleaned for UI
        let displayName = backendKey;
        if (displayName === 'The Guardian (Full Article)') {
            displayName = 'The Guardian';
        }
        // Store backend key for filtering; UI shows displayName
        listItem.dataset.source = backendKey;
        listItem.draggable = true;
        const displayCount = source.articleCount > 50 ? '50+' : source.articleCount;
        listItem.innerHTML = `
            <span class="drag-handle" style="cursor:grab; margin-right:6px;">≡</span>
            <span class="source-name">${sanitizeHtml(displayName)}</span>
            <span class="source-count" title="${source.articleCount} stored; capped at 50 shown">${displayCount}</span>
        `;
        listItem.addEventListener('click', function(e) {
            // Avoid triggering selection when starting a drag
            if (e.target.classList.contains('drag-handle')) return;
            handleSourceSelection(backendKey);
        });
        addDragEvents(listItem, sourcesList);
        fragment.appendChild(listItem);
    });

    // Clear and append all at once for better performance
    sourcesList.innerHTML = '';
    sourcesList.appendChild(fragment);

}

function addOnDemandApiSource(container, label, id, handler) {
    if (container.querySelector(`[data-source="${label}"]`)) return; // avoid duplicates
    const li = document.createElement('li');
    li.className = 'source-item api-source';
    li.dataset.source = label;
    li.id = id;
    li.innerHTML = `
        <span class="drag-handle" style="cursor:default; opacity:.35;">↺</span>
        <span class="source-name">${sanitizeHtml(label)}</span>
        <span class="source-count" title="On-demand fetch">API</span>`;
    li.addEventListener('click', (e) => {
        if (e.target.classList.contains('drag-handle')) return;
        handler();
    });
    container.appendChild(li);
}

async function fetchOnDemandApi(path) {
    try {
    uiUtils.setGlobalSpinner(true);
    // If path already contains /api prefix, avoid duplicating
    const base = window.API_BASE_URL || 'http://localhost:8080/api';
    const full = `${base}${path}`;
    const resp = await authUtils.makeAuthenticatedRequest(full);
        if (!resp || !resp.ok) throw new Error('API fetch failed');
        const articles = await resp.json();
        if (Array.isArray(articles) && articles.length) {
            // Prepend these articles to current list for visibility
            currentArticles = articles.concat(currentArticles);
            // Trim to cap size keeping newest/front items
            if (currentArticles.length > MAX_ARTICLES_IN_DOM) {
                currentArticles = currentArticles.slice(0, MAX_ARTICLES_IN_DOM);
            }
            const container = document.getElementById('articles-container');
            if (container) {
                container.innerHTML = '';
                renderArticlesGrid(currentArticles, false);
                // Safety: ensure DOM also capped
                capArticlesDom();
            }
            uiUtils.showToast(`Loaded ${articles.length} articles from on-demand source.`, 'success');
        } else {
            uiUtils.showToast('No new articles returned from source.', 'info');
        }
    } catch (e) {
        console.error('On-demand API fetch error', e);
    uiUtils.showToast('Failed to load on-demand source.', 'error');
    } finally {
    uiUtils.setGlobalSpinner(false);
    }
}

// Drag & Drop helpers for source ordering
function addDragEvents(item, container) {
    item.addEventListener('dragstart', (e) => {
        e.dataTransfer.effectAllowed = 'move';
        item.classList.add('dragging');
    });
    item.addEventListener('dragend', () => {
        item.classList.remove('dragging');
        persistSourceOrder(container);
    });
    container.addEventListener('dragover', (e) => {
        e.preventDefault();
        const after = getDragAfterElement(container, e.clientY);
        const dragging = document.querySelector('.source-item.dragging');
        if (!dragging) return;
        if (after == null) {
            container.appendChild(dragging);
        } else {
            container.insertBefore(dragging, after);
        }
    });
}

function getDragAfterElement(container, y) {
    const elements = [...container.querySelectorAll('.source-item:not(.dragging)')];
    return elements.reduce((closest, child) => {
        const box = child.getBoundingClientRect();
        const offset = y - box.top - box.height / 2;
        if (offset < 0 && offset > closest.offset) {
            return { offset, element: child };
        } else {
            return closest;
        }
    }, { offset: Number.NEGATIVE_INFINITY }).element;
}

function persistSourceOrder(container) {
    const order = [...container.querySelectorAll('.source-item')].map(li => li.dataset.source);
    localStorage.setItem('user_source_order', JSON.stringify(order));
}

// Resolve the correct backend source key to use for API calls
function resolveSourceKeyForFetch(selectedKey) {
    if (!selectedKey) return null;
    // If user clicked cleaned display label 'The Guardian', map to known backend key when present
    if (selectedKey === 'The Guardian') {
        // Prefer exact backend key in the loaded sources list if it exists
        const found = allSources.find(s => s.sourceName === 'The Guardian')
                  || allSources.find(s => s.sourceName === 'The Guardian (Full Article)');
        if (found) return found.sourceName;
        // Fallback to legacy key to maintain backward compatibility
        return 'The Guardian (Full Article)';
    }
    return selectedKey;
}

// Handle source selection
function handleSourceSelection(sourceName) {
    // Update current source
    if (currentSource === sourceName) {
        // Deselect if clicking the same source
        currentSource = null;
    } else {
        currentSource = sourceName;
    }
    
    // Update UI
    document.querySelectorAll('.source-item').forEach(item => {
        item.classList.remove('active');
        if (item.dataset.source === currentSource) {
            item.classList.add('active');
        }
    });
    
    // Reset pagination and reload news
    currentPage = 0;
    currentArticleIndex = 0;
    loadNews();
}

// Filter sources based on search input
function filterSources() {
    const searchInput = document.getElementById('source-search');
    const searchTerm = searchInput.value.toLowerCase().trim();
    
    const sourceItems = document.querySelectorAll('.source-item');
    
    sourceItems.forEach(item => {
        // Match against visible label text for clarity
        const labelEl = item.querySelector('.source-name');
        const sourceName = (labelEl ? labelEl.textContent : item.dataset.source).toLowerCase();
        
        if (sourceName.includes(searchTerm)) {
            item.classList.remove('hidden');
        } else {
            item.classList.add('hidden');
        }
    });
}

// Navigate to previous article
// Removed showPreviousArticle/showNextArticle navigation (single-article mode deprecated)

// Voice search functionality using Web Speech API
function startVoiceSearch() {
    // Check if browser supports speech recognition
    if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
    uiUtils.showToast('Voice search is not supported in your browser.', 'error');
        return;
    }
    
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    const recognition = new SpeechRecognition();
    
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.lang = 'en-US';
    
    const micIcon = document.getElementById('voice-search-btn');
    const searchInput = document.getElementById('search-input');
    
    // Visual feedback for listening state
    micIcon.classList.add('listening');
    micIcon.textContent = '🎙️';
    
    recognition.onresult = function(event) {
        const transcript = event.results[0][0].transcript;
        searchInput.value = transcript;
        handleSearch();
    };
    
    recognition.onerror = function(event) {
        console.error('Speech recognition error:', event.error);
    uiUtils.showToast('Voice search failed. Please try again.', 'error');
    };
    
    recognition.onend = function() {
        micIcon.classList.remove('listening');
        micIcon.textContent = '🎤';
    };
    
    recognition.start();
}

// Bias analysis functionality
async function analyzeBias(articleId) {
    try {
    // Concurrency + memory leak guards
    if (window._biasAnalysisInFlight) {
        try { window._biasAnalysisAbort && window._biasAnalysisAbort.abort(); } catch(e) { /* ignore */ }
    }
    const abortController = new AbortController();
    window._biasAnalysisAbort = abortController;
    window._biasAnalysisInFlight = true;
    setAnalyzeButtonLoading(articleId, true);
        // Get article data first
    const articleResponse = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/${articleId}`, { signal: abortController.signal });
        
        if (!articleResponse.ok) {
            throw new Error('Failed to fetch article');
        }
        
        const article = await articleResponse.json();
        
        // Show modal and loading state
        openBiasAnalysisModal();
    showBiasAnalysisLoading(true);
    uiUtils.setGlobalSpinner(true);
        
        // Display original article content
        displayOriginalArticle(article);
        
        // Call bias analysis API
        const biasResponse = await authUtils.makeAuthenticatedRequest(
            `${window.API_BASE_URL}/news/${articleId}/bias`, 
            { method: 'POST', signal: abortController.signal }
        );
        
        if (!biasResponse.ok) {
            const errorText = await biasResponse.text();
            console.error('Bias analysis API error:', {
                status: biasResponse.status,
                statusText: biasResponse.statusText,
                response: errorText
            });
            throw new Error(`Failed to analyze bias: ${biasResponse.status} - ${errorText}`);
        }

    const responseText = await biasResponse.text();
        console.log('Raw bias analysis response:', responseText);
        
        let biasAnalysis;
        try {
            // Clean up the response text in case it has formatting issues
            const cleanedResponse = responseText.replace(/<EOL>/g, '').replace(/\n/g, '').trim();
            console.log('Cleaned response:', cleanedResponse);
            
            biasAnalysis = JSON.parse(cleanedResponse);
            
            // Check if the response contains an error
            if (biasAnalysis.error) {
                if (biasAnalysis.error === 'RATE_LIMIT_EXCEEDED') {
                    // Show friendly quota message in modal UI and return early
                    const container = document.getElementById('ai-analysis-content');
                    container.innerHTML = `
                        <div class="alert alert-warning">
                            <h4>Daily Analysis Limit Reached</h4>
                            <p>${sanitizeHtml(biasAnalysis.message || 'The daily analysis quota has been reached. Please try again tomorrow.')}</p>
                        </div>
                    `;
                    showBiasAnalysisLoading(false);
                    uiUtils.setGlobalSpinner(false);
                    return; // stop further processing
                }
                throw new Error(`Backend error: ${biasAnalysis.error}`);
            }
        } catch (parseError) {
            console.error('JSON parsing error:', parseError);
            console.error('Response text that failed to parse:', responseText);
            throw new Error(`Invalid JSON response from server: ${parseError.message}`);
        }        // Display AI analysis results
        // Attach category-based class for theming (bias level colors)
        if (article.category) {
            const modal = document.getElementById('bias-analysis-modal');
            if (modal) {
                // Remove previous category-* classes
                modal.className = modal.className.replace(/category-[a-z]+/gi, '').trim();
                modal.classList.add('category-' + article.category.toLowerCase());
            }
        }
    // Display structured analysis (new format)
    // Only render if not aborted
    if (!abortController.signal.aborted) {
        displayBiasAnalysisStructured(biasAnalysis);
    }
    showBiasAnalysisLoading(false);
    uiUtils.setGlobalSpinner(false);
        
    } catch (error) {
        if (error.name === 'AbortError') {
            console.warn('Bias analysis aborted');
            return; // do not show UI errors on abort
        }
        console.error('Bias analysis error:', error);
        
        // Show detailed error message
        const container = document.getElementById('ai-analysis-content');
        
        let errorDetails = '';
        // Special case: surfaced 429 or structured rate limit from backend
        if (error.message.includes('RATE_LIMIT_EXCEEDED')) {
            errorDetails = `
                <div class="alert alert-warning">
                    <h4>Daily Analysis Limit Reached</h4>
                    <p>The daily analysis quota has been reached. Please try again tomorrow.</p>
                </div>
            `;
        } else
        if (error.message.includes('Backend error')) {
            errorDetails = `
                <div class="alert alert-error">
                    <h4>🤖 AI Service Error</h4>
                    <p>The Google Gemini AI service returned an error:</p>
                    <p><strong>Error:</strong> ${escapeHtml(error.message.replace('Backend error: ', ''))}</p>
                    
                    <p><strong>Common causes:</strong></p>
                    <ul>
                        <li><strong>Model name outdated:</strong> Google updated model names from 'gemini-pro' to 'gemini-1.5-flash'</li>
                        <li><strong>Invalid API key:</strong> Check if your Gemini API key is correct</li>
                        <li><strong>API quota exceeded:</strong> You may have hit usage limits</li>
                        <li><strong>Model not available:</strong> The requested model might not be accessible</li>
                    </ul>
                    
                    <p><strong>To fix this:</strong></p>
                    <ol>
                        <li>Update the model name to 'gemini-1.5-flash' in application.properties</li>
                        <li>Verify your Gemini API key is valid</li>
                        <li>Check Google AI Studio for API status</li>
                        <li>Restart the backend server after making changes</li>
                    </ol>
                </div>
            `;
        } else if (error.message.includes('JSON')) {
            errorDetails = `
                <div class="alert alert-error">
                    <h4>🔧 API Response Error</h4>
                    <p>The backend returned an invalid response format. This suggests:</p>
                    <ul>
                        <li>The Google Gemini API key might be missing or invalid</li>
                        <li>The bias analysis service is encountering an internal error</li>
                        <li>The response format doesn't match what the frontend expects</li>
                    </ul>
                    <p><strong>To fix this:</strong></p>
                    <ol>
                        <li>Check the backend console/logs for detailed error messages</li>
                        <li>Verify your Google Gemini API key is correctly configured</li>
                        <li>Ensure the bias analysis service is working properly</li>
                    </ol>
                    <p><em>Technical error: ${escapeHtml(error.message)}</em></p>
                    <details style="margin-top: 10px;">
                        <summary>Click to view technical details</summary>
                        <pre style="background: rgba(0,0,0,0.3); padding: 10px; border-radius: 4px; overflow-x: auto; margin-top: 5px;">${escapeHtml(JSON.stringify(error, null, 2))}</pre>
                    </details>
                </div>
            `;
        } else if (error.message.includes('Failed to fetch')) {
            errorDetails = `
                <div class="alert alert-error">
                    <h4>🌐 Connection Error</h4>
                    <p>Cannot connect to the backend server:</p>
                    <ul>
                        <li>The backend server might not be running</li>
                        <li>Network connectivity issues</li>
                        <li>CORS configuration problems</li>
                    </ul>
                    <p><strong>To fix this:</strong></p>
                    <ol>
                        <li>Start the backend server using <code>start-backend.bat</code></li>
                        <li>Verify the server is running on the correct port</li>
                        <li>Check your internet connection</li>
                    </ol>
                    <p><em>Error: ${escapeHtml(error.message)}</em></p>
                </div>
            `;
        } else {
            errorDetails = `
                <div class="alert alert-error">
                    <h4>⚠️ Bias Analysis Error</h4>
                    <p>An unexpected error occurred during bias analysis:</p>
                    <p><strong>Error:</strong> ${escapeHtml(error.message)}</p>
                    <p><strong>What to try:</strong></p>
                    <ul>
                        <li>Refresh the page and try again</li>
                        <li>Check the browser console for more details</li>
                        <li>Verify the backend server is running properly</li>
                    </ul>
                </div>
            `;
        }
        
        container.innerHTML = errorDetails;
    showBiasAnalysisLoading(false);
    uiUtils.setGlobalSpinner(false);
    uiUtils.showToast('Bias analysis failed. See modal for details.', 'error');
    }
    finally {
        setAnalyzeButtonLoading(articleId, false);
        if (window._biasAnalysisAbort === abortController) {
            window._biasAnalysisInFlight = false;
        }
    }
}

// Text-to-Speech functionality
function readArticle(articleId) {
    // Check if we're already reading this specific article
    if (isSpeaking && currentSpeech) {
        // If already speaking, stop and hide controls instead of starting new speech
        stopReadingArticle(articleId);
        return;
    }
    
    // Hide the Listen button immediately and show loading state
    const listenBtn = document.getElementById(`listen-btn-${articleId}`);
    if (listenBtn) {
        listenBtn.style.display = 'none';
    }
    
    // Update listen button to show loading state
    updateListenButton(articleId, 'loading');
    
    // First fetch the article content
    fetchArticleForTTS(articleId);
}

async function fetchArticleForTTS(articleId) {
    try {
        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/${articleId}`);
        
        if (!response.ok) {
            throw new Error('Failed to fetch article');
        }
        
        const article = await response.json();
        startTextToSpeech(article, articleId);
        
    } catch (error) {
        console.error('Error fetching article for TTS:', error);
    uiUtils.showToast('Failed to load article for reading.', 'error');
        updateListenButton(articleId, 'stopped');
    }
}

// Start text-to-speech for article
function startTextToSpeech(article, articleId) {
    // Stop any currently playing speech and hide old controls
    if (currentSpeech) {
        speechSynthesis.cancel();
        // Hide all TTS controls first
        const allControls = document.querySelectorAll('[id^="tts-controls-"]');
        allControls.forEach(control => control.classList.add('hidden'));
        // Show all Listen buttons
        const allListenBtns = document.querySelectorAll('[id^="listen-btn-"]');
        allListenBtns.forEach(btn => {
            btn.style.display = 'inline-block';
            btn.textContent = 'Listen';
            btn.disabled = false;
        });
    }
    
    // Create speech synthesis utterance
    const textToRead = `${article.title}. ${article.content}`;
    currentSpeech = new SpeechSynthesisUtterance(textToRead);
    
    // Configure speech settings
    currentSpeech.rate = 0.9;
    currentSpeech.pitch = 1;
    currentSpeech.volume = 1;
    
    // Apply preferred voice if available
    const preferredVoice = getPreferredVoice();
    if (preferredVoice) {
        currentSpeech.voice = preferredVoice;
        console.log(`Using preferred voice: ${preferredVoice.name}`);
    }
    
    // Hide the Listen button for this article and show TTS controls
    const listenBtn = document.getElementById(`listen-btn-${articleId}`);
    if (listenBtn) {
        listenBtn.style.display = 'none';
    }
    
    // Show TTS controls for this article
    const controlsElement = document.getElementById(`tts-controls-${articleId}`);
    if (controlsElement) {
        controlsElement.classList.remove('hidden');
    }
    
    // Event handlers for speech
    currentSpeech.onstart = function() {
        isSpeaking = true;
        updateTTSControls(articleId, 'playing');
    };
    
    currentSpeech.onend = function() {
        isSpeaking = false;
        currentSpeech = null;
        // Hide TTS controls and show Listen button
        if (controlsElement) {
            controlsElement.classList.add('hidden');
        }
        if (listenBtn) {
            listenBtn.style.display = 'inline-block';
            listenBtn.textContent = 'Listen';
            listenBtn.disabled = false;
        }
    };
    
    currentSpeech.onerror = function(event) {
        console.error('Speech synthesis error:', event);
    uiUtils.showToast('Text-to-speech failed. Please try again.', 'error');
        isSpeaking = false;
        currentSpeech = null;
        // Hide TTS controls and show Listen button on error
        if (controlsElement) {
            controlsElement.classList.add('hidden');
        }
        if (listenBtn) {
            listenBtn.style.display = 'inline-block';
            listenBtn.textContent = 'Listen';
            listenBtn.disabled = false;
        }
    };
    
    // Start speaking
    speechSynthesis.speak(currentSpeech);
}

// Pause or resume article reading
function pauseResumeArticle(articleId) {
    if (speechSynthesis.speaking && !speechSynthesis.paused) {
        speechSynthesis.pause();
        updateTTSControls(articleId, 'paused');
    } else if (speechSynthesis.paused) {
        speechSynthesis.resume();
        updateTTSControls(articleId, 'playing');
    }
}

// Stop article reading
function stopReadingArticle(articleId) {
    speechSynthesis.cancel();
    currentSpeech = null;
    isSpeaking = false;
    
    // Hide TTS controls for this article
    const controlsElement = document.getElementById(`tts-controls-${articleId}`);
    if (controlsElement) {
        controlsElement.classList.add('hidden');
    }
    
    // Show the Listen button for this article
    const listenBtn = document.getElementById(`listen-btn-${articleId}`);
    if (listenBtn) {
        listenBtn.style.display = 'inline-block';
        listenBtn.textContent = 'Listen';
        listenBtn.disabled = false;
    }
}

// Update TTS control buttons
function updateTTSControls(articleId, state) {
    const controlsElement = document.getElementById(`tts-controls-${articleId}`);
    if (!controlsElement) return;
    
    const pauseBtn = controlsElement.querySelector('.tts-btn');
    if (state === 'playing') {
        pauseBtn.textContent = 'Pause';
    } else if (state === 'paused') {
        pauseBtn.textContent = 'Resume';
    }
}

// Hide TTS controls
function hideTTSControls(articleId) {
    const controlsElement = document.getElementById(`tts-controls-${articleId}`);
    if (controlsElement) {
        controlsElement.classList.add('hidden');
    }
}

// Centralized preferred voice handling
let availableVoices = [];
let preferredVoiceName = null;

function setupVoicePreference() {
    preferredVoiceName = localStorage.getItem('user_preferred_voice') || null;
    function loadVoices() {
        availableVoices = speechSynthesis.getVoices();
    }
    loadVoices();
    speechSynthesis.addEventListener('voiceschanged', loadVoices);
}

function getPreferredVoice() {
    if (!preferredVoiceName) return null;
    if (!availableVoices || availableVoices.length === 0) {
        availableVoices = speechSynthesis.getVoices();
    }
    return availableVoices.find(v => v.name === preferredVoiceName) || null;
}

// Modal management functions
function openBiasAnalysisModal() {
    const modal = document.getElementById('bias-analysis-modal');
    modal.classList.add('active');
    document.body.style.overflow = 'hidden'; // Prevent background scrolling
}

function closeBiasAnalysisModal() {
    const modal = document.getElementById('bias-analysis-modal');
    modal.classList.remove('active');
    document.body.style.overflow = 'auto'; // Re-enable scrolling
    
    // Clear modal content
    document.getElementById('original-article-content').innerHTML = '';
    document.getElementById('ai-analysis-content').innerHTML = '';
    // Abort any in-flight fetch & reset flags
    try { if (window._biasAnalysisAbort) { window._biasAnalysisAbort.abort(); } } catch(e){/* ignore */}
    window._biasAnalysisInFlight = false;
    // Hide loading states if stuck
    showBiasAnalysisLoading(false);
    uiUtils.setGlobalSpinner(false);
}

function showBiasAnalysisLoading(show) {
    const loadingElement = document.getElementById('bias-analysis-loading');
    const contentElement = document.getElementById('bias-analysis-content');
    
    if (show) {
        loadingElement.classList.remove('hidden');
        contentElement.classList.add('hidden');
    } else {
        loadingElement.classList.add('hidden');
        contentElement.classList.remove('hidden');
    }
}

// Defensive: if user switches tabs while loading and analysis completes/aborts, ensure spinner resets
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        // If no in-flight request but spinner showing, reset
        const loadingElement = document.getElementById('bias-analysis-loading');
        if (!window._biasAnalysisInFlight && loadingElement && !loadingElement.classList.contains('hidden')) {
            showBiasAnalysisLoading(false);
            uiUtils.setGlobalSpinner(false);
        }
    }
});

// Display original article in modal
function displayOriginalArticle(article) {
    const container = document.getElementById('original-article-content');
    const publishDate = new Date(article.publishDate).toLocaleDateString();
    
    // Strip HTML from content for clean display
    const cleanContent = stripHtml(article.content);
    
    container.innerHTML = `
        <h4>${sanitizeHtml(article.title)}</h4>
        <div class="article-meta">
            <span class="article-source">${sanitizeHtml(article.source)}</span>
            <span class="article-date">${publishDate}</span>
        </div>
        <div class="article-full-content">
            ${sanitizeHtml(cleanContent)}
        </div>
    `;
}

// Display AI bias analysis results with new structure
function displayBiasAnalysis(analysis) {
    const container = document.getElementById('ai-analysis-content');
    
    try {
        // Parse JSON if it's a string
        const parsedAnalysis = typeof analysis === 'string' ? JSON.parse(analysis) : analysis;
        
        const htmlContent = `
            ${createQuickSummarySection(parsedAnalysis.quick_summary)}
            ${createDetailedAnalysisSection(parsedAnalysis.detailed_analysis)}
        `;
        
        container.innerHTML = htmlContent;
        
    } catch (error) {
        console.error('Error parsing bias analysis:', error);
        const errorMessage = '<p class="alert alert-error">Failed to parse bias analysis results.</p>';
        container.innerHTML = errorMessage;
    }
}

// XAI-Enhanced structured display for bias analysis
function displayBiasAnalysisStructured(data) {
    const container = document.getElementById('ai-analysis-content');
    const sanitize = sanitizeHtml;
    try {
        // Extract new concise fields
        const biasLevel = data.biasLevel || 'Neutral';
        const confidence = data.confidence || 'Medium';
        const reasonForBias = data.reasonForBias || 'No specific reason provided.';
        const xaiJustification = data.xaiJustification || 'AI reasoning not available.';
        const missingContext = Array.isArray(data.missingContext) ? data.missingContext.slice(0, 3) : []; // Max 3 items
        const balancedPerspective = data.balancedPerspective || 'No balanced perspective provided.';
        const selfReflection = data.selfReflection || 'No self-reflection provided.';

        const levelClass = biasLevel.toLowerCase();
        const confidenceClass = confidence.toLowerCase();

        const missingList = missingContext.map(item => `<li>${sanitize(item)}</li>`).join('');
        
        container.innerHTML = `
            <div class="xai-bias-analysis">
                <!-- Bias Level & Confidence -->
                <div class="analysis-header">
                    <div class="bias-rating">
                        <span class="bias-level ${levelClass}">${sanitize(biasLevel)} Bias</span>
                        <span class="confidence-indicator ${confidenceClass}" title="AI Confidence: ${confidence}">
                            ${confidence} Confidence
                        </span>
                    </div>
                </div>

                <!-- Reason for Bias (2-3 lines) -->
                <div class="analysis-section">
                    <h4>📋 Why This Rating?</h4>
                    <p class="concise-text">${sanitize(reasonForBias)}</p>
                </div>

                <!-- XAI Justification (KEY FEATURE) -->
                <div class="analysis-section xai-highlight">
                    <h4>🧠 AI Reasoning Process (XAI)</h4>
                    <p class="concise-text xai-explanation">${sanitize(xaiJustification)}</p>
                    <small class="xai-note">This shows how the AI made its decision - a key Explainable AI feature</small>
                </div>

                <!-- Missing Context (Max 3 points) -->
                ${missingContext.length > 0 ? `
                <div class="analysis-section">
                    <h4>❓ Missing Context</h4>
                    <ul class="concise-list">${missingList}</ul>
                </div>
                ` : ''}

                <!-- Balanced Perspective (2-3 lines) -->
                <div class="analysis-section">
                    <h4>⚖️ Balanced Perspective</h4>
                    <p class="concise-text">${sanitize(balancedPerspective)}</p>
                </div>

                <!-- AI Self-Reflection (2-3 lines) -->
                <div class="analysis-section">
                    <h4>🤔 AI Self-Assessment</h4>
                    <p class="concise-text self-reflection">${sanitize(selfReflection)}</p>
                </div>
            </div>
        `;

        // Reveal content
        document.getElementById('bias-analysis-content').classList.remove('hidden');
    } catch (e) {
        console.error('Error displaying XAI bias analysis:', e);
        container.innerHTML = '<p class="alert alert-error">Failed to display analysis.</p>';
    }
}

// Smoothly expand/collapse the structured detailed analysis content
function toggleStructuredDetailedAnalysis() {
    const container = document.getElementById('structured-detailed');
    const content = document.getElementById('structured-detailed-content');
    const arrow = document.getElementById('structured-toggle-arrow');
    if (!container || !content || !arrow) return;

    const isOpen = container.classList.toggle('open');
    if (isOpen) {
        content.style.maxHeight = content.scrollHeight + 'px';
        content.setAttribute('aria-hidden', 'false');
        arrow.textContent = '▲';
    } else {
        content.style.maxHeight = '0px';
        content.setAttribute('aria-hidden', 'true');
        arrow.textContent = '▼';
    }
}

// Create quick summary section HTML (always visible)
function createQuickSummarySection(quickSummary) {
    if (!quickSummary) return '';
    
    const biasLevel = (quickSummary.bias_level || 'Unknown').toLowerCase();
    
    return `
        <div class="analysis-section quick-summary">
            <h4>🎯 Quick Summary</h4>
            <div class="bias-overview">
                <p><strong>Bias Level:</strong> <span class="bias-level ${biasLevel}">${quickSummary.bias_level || 'Unknown'}</span></p>
                <p><strong>Primary Bias:</strong> ${escapeHtml(quickSummary.bias_summary || 'No significant bias detected.')}</p>
                <p><strong>Alternative View:</strong> ${escapeHtml(quickSummary.counter_narrative || 'Multiple perspectives should be considered.')}</p>
            </div>
        </div>
    `;
}

// Create detailed analysis section HTML (expandable)
function createDetailedAnalysisSection(detailedAnalysis) {
    if (!detailedAnalysis) return '';
    
    return `
        <div class="analysis-section detailed-analysis">
            <h4 onclick="toggleDetailedAnalysis()" style="cursor: pointer; user-select: none;">
                📊 Detailed Analysis <span id="toggle-arrow">▼</span>
            </h4>
            <div id="detailed-content" style="display: none;">
                
                ${detailedAnalysis.explanation ? `
                    <div class="detail-subsection">
                        <p><strong>Explanation:</strong></p>
                        <p>${escapeHtml(detailedAnalysis.explanation)}</p>
                    </div>
                ` : ''}
                
                ${detailedAnalysis.missing_context && detailedAnalysis.missing_context.length > 0 ? `
                    <div class="detail-subsection">
                        <p><strong>Missing Context:</strong></p>
                        <ul>
                            ${detailedAnalysis.missing_context.map(context => `<li>${escapeHtml(context)}</li>`).join('')}
                        </ul>
                    </div>
                ` : ''}
                
                ${detailedAnalysis.balanced_conclusion ? `
                    <div class="detail-subsection">
                        <p><strong>Balanced Perspective:</strong></p>
                        <p>${escapeHtml(detailedAnalysis.balanced_conclusion)}</p>
                    </div>
                ` : ''}
                
            </div>
        </div>
    `;
}

// Toggle detailed analysis visibility
function toggleDetailedAnalysis() {
    const content = document.getElementById('detailed-content');
    const arrow = document.getElementById('toggle-arrow');
    
    if (content.style.display === 'none') {
        content.style.display = 'block';
        arrow.textContent = '▲';
    } else {
        content.style.display = 'none';
        arrow.textContent = '▼';
    }
}

// Utility functions
function showLoadingIndicator(show) {
    const loadingElement = document.getElementById('loading-indicator');
    if (show) {
        loadingElement.classList.remove('hidden');
    } else {
        loadingElement.classList.add('hidden');
    }
}

/**
 * Strip HTML tags from content as a frontend safeguard
 * This provides an additional layer of protection against any HTML that might slip through
 */
function stripHtml(html) {
    if (!html || typeof html !== 'string') {
        return '';
    }
    
    try {
        // Create a temporary element to safely extract text content
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = html;
        
        // Extract plain text content
        let textContent = tempDiv.textContent || tempDiv.innerText || '';
        
        // Additional cleanup using regex as backup
        textContent = textContent
            .replace(/\s+/g, ' ')           // Replace multiple spaces with single space
            .replace(/^\s+|\s+$/g, '')      // Trim leading/trailing whitespace
            .replace(/\n+/g, ' ')           // Replace newlines with spaces
            .replace(/\t+/g, ' ');          // Replace tabs with spaces
        
        return textContent;
        
    } catch (error) {
        console.warn('Error stripping HTML:', error);
        // Fallback to regex-based cleaning
        return html
            .replace(/<[^>]*>/g, ' ')       // Remove HTML tags
            .replace(/&[a-zA-Z0-9#]+;/g, ' ')  // Remove HTML entities
            .replace(/\s+/g, ' ')           // Replace multiple spaces
            .trim();                        // Trim whitespace
    }
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Sanitize HTML content while allowing basic formatting
function sanitizeHtml(text) {
    if (!text) return '';
    
    // For now, allow basic HTML but escape dangerous elements
    // This is a simple approach - for production, consider using a proper HTML sanitization library
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#x27;')
        // Allow basic formatting to be displayed
        .replace(/&lt;br\s*\/?&gt;/gi, '<br>')
        .replace(/&lt;\/br&gt;/gi, '</br>')
        .replace(/&lt;p&gt;/gi, '<p>')
        .replace(/&lt;\/p&gt;/gi, '</p>')
        .replace(/&lt;b&gt;/gi, '<b>')
        .replace(/&lt;\/b&gt;/gi, '</b>')
        .replace(/&lt;strong&gt;/gi, '<strong>')
        .replace(/&lt;\/strong&gt;/gi, '</strong>')
        .replace(/&lt;i&gt;/gi, '<i>')
        .replace(/&lt;\/i&gt;/gi, '</i>')
        .replace(/&lt;em&gt;/gi, '<em>')
        .replace(/&lt;\/em&gt;/gi, '</em>');
}

// Hybrid infinite scroll evaluation
function evaluateHybridInfiniteScroll() {
    const trigger = document.getElementById('infinite-scroll-trigger');
    const loadMoreBtn = document.getElementById('load-more-btn');
    if (!trigger || !loadMoreBtn) return;

    if (articlesLoadedOnPage >= ARTICLES_PER_PAGE) {
        // Switch to manual mode
        trigger.classList.add('hidden');
        loadMoreBtn.classList.remove('hidden');
    } else {
        // Ensure infinite scroll active
        trigger.classList.remove('hidden');
        loadMoreBtn.classList.add('hidden');
    }
}

// Reading List functionality
let userReadingLists = [];
let readingListDropdown = null;

// Load user's reading lists
async function loadUserReadingLists() {
    try {
        const token = authUtils.getAuthToken && authUtils.getAuthToken();
        if (!token) return [];

        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/reading-lists`, {
            method: 'GET'
        });
        if (!response || !response.ok) return [];

        userReadingLists = await response.json();
        return userReadingLists;
    } catch (error) {
        console.error('Error loading reading lists:', error);
        return [];
    }
}

// Show reading list dropdown
async function showReadingListDropdown(articleId) {
    try {
        // Load reading lists if not already loaded
        if (userReadingLists.length === 0) {
            await loadUserReadingLists();
        }

        // If no reading lists, prompt to create one
        if (userReadingLists.length === 0) {
            if (confirm('You don\'t have any reading lists yet. Would you like to create one?')) {
                window.location.href = 'reading-lists.html';
            }
            return;
        }

        // Create dropdown if it doesn't exist
        if (!readingListDropdown) {
            createReadingListDropdown();
        }

        // Position dropdown near the button
        const button = document.querySelector(`[onclick="showReadingListDropdown('${articleId}')"]`);
        if (button) {
            const rect = button.getBoundingClientRect();
            readingListDropdown.style.top = (rect.bottom + window.scrollY + 5) + 'px';
            readingListDropdown.style.left = rect.left + 'px';
        }

        // Populate dropdown with reading lists
        populateReadingListDropdown(articleId);

        // Show dropdown
        readingListDropdown.style.display = 'block';

        // Close dropdown when clicking outside
        setTimeout(() => {
            document.addEventListener('click', closeReadingListDropdown, { once: true });
        }, 100);

    } catch (error) {
        console.error('Error showing reading list dropdown:', error);
    uiUtils.showToast('Failed to load reading lists.', 'error');
    }
}

// Create reading list dropdown element
function createReadingListDropdown() {
    readingListDropdown = document.createElement('div');
    readingListDropdown.className = 'reading-list-dropdown glass-card';
    readingListDropdown.style.display = 'none';
    readingListDropdown.innerHTML = `
        <div class="dropdown-header">
            <h4>Add to Reading List</h4>
        </div>
        <div class="dropdown-content" id="readingListOptions">
            <!-- Options will be populated here -->
        </div>
        <div class="dropdown-footer">
            <button class="btn btn-small btn-secondary" onclick="window.location.href='reading-lists.html'">
                <i class="fas fa-plus"></i> Create New List
            </button>
        </div>
    `;
    document.body.appendChild(readingListDropdown);
}

// Populate dropdown with reading lists
function populateReadingListDropdown(articleId) {
    const optionsContainer = document.getElementById('readingListOptions');
    if (!optionsContainer) return;

    optionsContainer.innerHTML = '';

    userReadingLists.forEach(list => {
        const option = document.createElement('div');
        option.className = 'dropdown-option';
        option.innerHTML = `
            <div class="list-option-info">
                <div class="list-color" style="background-color: ${list.colorTheme};"></div>
                <span class="list-name">${escapeHtml(list.name)}</span>
                <span class="list-count">(${list.articleCount})</span>
            </div>
        `;
        
        option.addEventListener('click', () => {
            addArticleToReadingList(articleId, list.id, list.name);
            closeReadingListDropdown();
        });

        optionsContainer.appendChild(option);
    });
}

// Close reading list dropdown
function closeReadingListDropdown() {
    if (readingListDropdown) {
        readingListDropdown.style.display = 'none';
    }
}

// Add article to reading list
async function addArticleToReadingList(articleId, listId, listName) {
    try {
        const token = authUtils.getAuthToken && authUtils.getAuthToken();
        if (!token) {
            uiUtils.showToast('Please login to add articles to reading lists.', 'error');
            return;
        }

        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/reading-lists/${listId}/articles/${articleId}`, {
            method: 'PUT'
        });

        if (!response || !response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to add article to reading list');
        }

        // Show success feedback
        showReadingListFeedback(`Article added to "${listName}"`);

        // Refresh reading lists cache
        await loadUserReadingLists();

    } catch (error) {
        console.error('Error adding article to reading list:', error);
        if (error.message.includes('already in this reading list')) {
            uiUtils.showToast('Article is already in this reading list.', 'warning');
        } else {
            uiUtils.showToast('Failed to add article to reading list.', 'error');
        }
    }
}

// Show reading list feedback
function showReadingListFeedback(message) {
    const toast = document.createElement('div');
    toast.className = 'reading-list-toast glass-card';
    toast.innerHTML = `
        <i class="fas fa-list"></i>
        <span>${message}</span>
    `;
    
    document.body.appendChild(toast);
    
    // Show toast
    setTimeout(() => toast.classList.add('show'), 100);
    
    // Remove toast after 3 seconds
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => document.body.removeChild(toast), 300);
    }, 3000);
}

// Utility function for escaping HTML (reused from other parts)
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Export functions for global access
Object.assign(window.newsUtils, {
    loadNews,
    handleSearch,
    handleCategoryChange,
    analyzeBias,
    readArticle,
    pauseResumeArticle,
    stopReadingArticle,
    updateListenButton,
    toggleBookmark,
    checkBookmarkStatus,
    showReadingListDropdown,
    addArticleToReadingList
});

// Update the Listen button text and state
function updateListenButton(articleId, state) {
    const listenBtn = document.getElementById(`listen-btn-${articleId}`);
    if (!listenBtn) return;
    
    switch (state) {
        case 'loading':
            listenBtn.textContent = 'Loading...';
            listenBtn.disabled = true;
            break;
        case 'playing':
            listenBtn.textContent = 'Stop';
            listenBtn.disabled = false;
            break;
        case 'stopped':
        case 'idle':
        default:
            listenBtn.textContent = 'Listen';
            listenBtn.disabled = false;
            break;
    }
}

// Initialize bookmark status for an article
async function initializeBookmarkStatus(articleId) {
    try {
        const isBookmarked = await checkBookmarkStatus(articleId);
        updateBookmarkButton(articleId, isBookmarked);
    } catch (error) {
        console.error('Error initializing bookmark status:', error);
    }
}

// Bookmark functionality
async function toggleBookmark(articleId) {
    try {
        console.log('Toggling bookmark for article:', articleId);
        const token = authUtils.getAuthToken && authUtils.getAuthToken();
        if (!token) {
            console.warn('No authentication token found');
            showBookmarkError('Please login to bookmark articles.');
            return;
        }

        console.log('Making bookmark API call...');
    const btn = document.getElementById(`bookmark-btn-${articleId}`);
    if (btn) btn.classList.add('loading');
        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/user/bookmarks/${articleId}`, {
            method: 'POST'
        });

        if (!response) {
            showBookmarkError('Authentication expired. Please login again.');
            return;
        }

        console.log('Bookmark API response status:', response.status);

        if (!response.ok) {
            const errorText = await response.text();
            console.error('Bookmark API error:', response.status, errorText);
            throw new Error(`Failed to toggle bookmark: ${response.status} - ${errorText}`);
        }

        const result = await response.json();
        console.log('Bookmark API result:', result);
        
        // Update bookmark button appearance
    updateBookmarkButton(articleId, result.bookmarked);
        
        // Show user feedback
        showBookmarkFeedback(result.bookmarked);
        
        return result;
        
    } catch (error) {
        console.error('Error toggling bookmark:', error);
        showBookmarkError(`Failed to update bookmark: ${error.message}`);
    } finally {
        const btn = document.getElementById(`bookmark-btn-${articleId}`);
        if (btn) btn.classList.remove('loading');
    }
}

// Check if an article is bookmarked
async function checkBookmarkStatus(articleId) {
    try {
        const token = authUtils.getAuthToken && authUtils.getAuthToken();
        if (!token) {
            return false;
        }

        // Use central auth utility to ensure consistent headers
        const response = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/user/bookmarks/check/${articleId}`, {
            method: 'GET'
        });
        if (!response) { // makeAuthenticatedRequest may logout & return undefined on 401
            return false;
        }

        if (!response.ok) {
            return false;
        }

        const result = await response.json();
        return result.bookmarked;
        
    } catch (error) {
        console.error('Error checking bookmark status:', error);
        return false;
    }
}

// Update bookmark button appearance
function updateBookmarkButton(articleId, isBookmarked) {
    const bookmarkBtn = document.getElementById(`bookmark-btn-${articleId}`);
    if (bookmarkBtn) {
        const icon = bookmarkBtn.querySelector('i');
        const text = bookmarkBtn.querySelector('.btn-text');
        
        if (isBookmarked) {
            bookmarkBtn.classList.add('bookmarked','saved');
            if (icon) icon.className = 'fas fa-bookmark';
            if (text) text.textContent = 'Saved';
        } else {
            bookmarkBtn.classList.remove('bookmarked','saved');
            if (icon) icon.className = 'far fa-bookmark';
            if (text) text.textContent = 'Save';
        }
    }
}

// Show bookmark feedback to user
function showBookmarkFeedback(isBookmarked) {
    // Create a simple toast notification
    const toast = document.createElement('div');
    toast.className = 'bookmark-toast glass-card';
    toast.innerHTML = `
        <i class="fas fa-${isBookmarked ? 'bookmark' : 'bookmark-slash'}"></i>
        <span>Article ${isBookmarked ? 'saved' : 'removed'}</span>
    `;
    
    document.body.appendChild(toast);
    
    // Show toast
    setTimeout(() => toast.classList.add('show'), 100);
    
    // Remove toast after 3 seconds
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => document.body.removeChild(toast), 300);
    }, 3000);
}

// Export article functionality
async function exportArticle(articleId) {
    try {
        console.log('Exporting article:', articleId);
        
        // Find the article data from currentArticles array
        const article = currentArticles.find(a => a.id === articleId);
        if (!article) {
            console.error('Article not found for export:', articleId);
            uiUtils.showToast('Article not found for export.', 'error');
            return;
        }

        // Show export modal with article data
        if (window.exportModal) {
            window.exportModal.show(article);
        } else {
            console.error('Export modal not available');
            uiUtils.showToast('Export functionality not available.', 'error');
        }
        
    } catch (error) {
        console.error('Error exporting article:', error);
    uiUtils.showToast('Failed to export article. Please try again.', 'error');
    }
}

// Show bookmark error
function showBookmarkError(message) {
    const toast = document.createElement('div');
    toast.className = 'bookmark-toast error glass-card';
    toast.innerHTML = `
        <i class="fas fa-exclamation-triangle"></i>
        <span>${message}</span>
    `;
    
    document.body.appendChild(toast);
    
    // Show toast
    setTimeout(() => toast.classList.add('show'), 100);
    
    // Remove toast after 4 seconds for errors
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => document.body.removeChild(toast), 300);
    }, 4000);
}

// Read More / Read Less (event delegation)
document.addEventListener('click', (e) => {
    const btn = e.target.closest('.read-more-btn');
    if (!btn) return;
    const card = btn.closest('.article-card');
    if (!card) return;
    const summary = card.querySelector('.article-summary');
    if (!summary) return;
    const expanded = summary.classList.toggle('expanded');
    summary.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    // Guardian full article special handling: load HTML only when expanding first time
    if (summary.classList.contains('guardian-full')) {
        try {
            if (expanded) {
                const loaded = summary.getAttribute('data-html-loaded') === 'true';
                if (!loaded) {
                    const encoded = summary.getAttribute('data-full-html');
                    if (encoded) {
                        const html = decodeURIComponent(encoded);
                        summary.innerHTML = safeArticleHtml(html);
                        summary.setAttribute('data-html-loaded','true');
                    }
                }
            } else {
                // Collapsing -> revert to text preview to re-enable clamp & performance
                const preview = decodeURIComponent(summary.getAttribute('data-preview-text') || '');
                summary.innerHTML = sanitizeHtml(preview);
            }
        } catch(err) { console.warn('Guardian expand error', err); }
    }
    btn.textContent = expanded ? 'Read Less' : 'Read More';
});

function safeArticleHtml(html){
    if(!html) return '';
    return html
        .replace(/<script[^>]*>[\s\S]*?<\/script>/gi,'')
        .replace(/<style[^>]*>[\s\S]*?<\/style>/gi,'')
        .replace(/ on[a-zA-Z]+="[^"]*"/g,'')
        .replace(/ on[a-zA-Z]+='[^']*'/g,'');
}

// Hide Read More if not needed
function finalizeCardUI(card){
    try {
        const summary = card.querySelector('.article-summary');
        const btn = card.querySelector('.read-more-btn');
        if(!summary || !btn) return;
        // Clone to measure full height without clamp
        const clone = summary.cloneNode(true);
        clone.style.position='absolute';
        clone.style.visibility='hidden';
        clone.style.zIndex='-1';
        clone.classList.add('expanded'); // remove clamp
        document.body.appendChild(clone);
        const fullHeight = clone.scrollHeight;
        document.body.removeChild(clone);
        const collapsedHeight = summary.scrollHeight; // clamped height
        if (fullHeight <= collapsedHeight + 4) {
            btn.style.display='none';
        }
        // Ensure button remains for Guardian full articles even if initial preview fits 5 lines (so user can load rest)
        if (summary.classList.contains('guardian-full')) {
            const hasFull = summary.getAttribute('data-full-html');
            if (hasFull) btn.style.display='';
        }
    } catch(e) { /* ignore measurement errors */ }
}

// ---------------- Chat Bot UI for article-specific conversation ----------------
function openChatModal(articleId) {
    // If a modal already exists, remove it first
    closeChatModal();

    const article = currentArticles.find(a => a.id === articleId) || {};
    const title = article.title || 'Article Chat';

    const modal = document.createElement('div');
    modal.id = 'article-chat-modal';
    modal.className = 'modal chat-modal active';
    modal.innerHTML = `
        <div class="modal-content glass-card">
            <div class="modal-header">
                <h3>Chat about: ${sanitizeHtml(title)}</h3>
                <button class="btn btn-small" id="chat-close-btn">Close</button>
            </div>
            <div class="chat-body" id="chat-body" style="max-height: 50vh; overflow:auto; margin:10px 0;">
                <div class="chat-system-msg">You can ask questions about the article. Be concise.</div>
            </div>
            <div class="chat-input-row">
                <input id="chat-input" type="text" placeholder="Ask the chat bot about this article..." style="width:80%; padding:8px;" />
                <button id="chat-send-btn" class="btn btn-primary">Send</button>
            </div>
        </div>
    `;
    document.body.appendChild(modal);

    document.getElementById('chat-close-btn').addEventListener('click', closeChatModal);
    document.getElementById('chat-send-btn').addEventListener('click', () => sendChatMessage(articleId));
    document.getElementById('chat-input').addEventListener('keypress', (e) => { if (e.key === 'Enter') sendChatMessage(articleId); });
}

function closeChatModal() {
    const existing = document.getElementById('article-chat-modal');
    if (existing) existing.remove();
}

function appendChatMessage(text, from = 'bot') {
    const body = document.getElementById('chat-body');
    if (!body) return;
    const msg = document.createElement('div');
    msg.className = 'chat-msg ' + (from === 'bot' ? 'bot' : 'user');
    msg.style.margin = '6px 0';
    msg.innerHTML = `<div class="msg-content">${sanitizeHtml(text)}</div>`;
    body.appendChild(msg);
    body.scrollTop = body.scrollHeight;
}

async function sendChatMessage(articleId) {
    try {
        const input = document.getElementById('chat-input');
        if (!input) return;
        const text = input.value.trim();
        if (!text) return;
        appendChatMessage(text, 'user');
        input.value = '';
        appendChatMessage('Thinking…', 'bot');

        const resp = await authUtils.makeAuthenticatedRequest(`${window.API_BASE_URL}/news/${articleId}/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text })
        });

        const botNodes = document.querySelectorAll('#chat-body .chat-msg.bot');
        // Remove the last temporary 'Thinking…' message
        if (botNodes && botNodes.length) {
            const last = botNodes[botNodes.length - 1];
            if (last && last.textContent && last.textContent.includes('Thinking')) last.remove();
        }

        if (!resp || !resp.ok) {
            const txt = resp ? await resp.text() : 'No response from server';
            appendChatMessage('Error: ' + txt, 'bot');
            return;
        }

        const data = await resp.json();
        if (data && data.reply) {
            appendChatMessage(data.reply, 'bot');
        } else if (data && data.error) {
            appendChatMessage('Error: ' + data.error, 'bot');
        } else {
            appendChatMessage('No reply from chat service.', 'bot');
        }

    } catch (e) {
        console.error('Chat send error', e);
        appendChatMessage('An unexpected error occurred.', 'bot');
    }
}

