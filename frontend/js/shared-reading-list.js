// Shared Reading List View functionality (public access, no authentication required)
class SharedReadingListView {
    constructor() {
        if (!window.API_BASE_URL) { 
            window.API_BASE_URL = 'http://localhost:8080/api'; 
        }
        this.baseUrl = window.API_BASE_URL;
        this.shareToken = this.getShareTokenFromUrl();
        this.listData = null;
        this.articles = [];
        this.init();
    }

    init() {
        // Hide loading overlay initially
        this.hideLoading();
        
        if (!this.shareToken) {
            this.showPrivateMessage('Invalid share link');
            return;
        }
        
        this.loadPublicList();
    }

    getShareTokenFromUrl() {
        const urlParams = new URLSearchParams(window.location.search);
        return urlParams.get('token');
    }

    async loadPublicList() {
        try {
            this.showLoading();
            
            console.log('Loading public list with share token:', this.shareToken);
            
            // Load public reading list with articles
            const fetchFn = (window.authUtils && window.authUtils.safeFetch) ? window.authUtils.safeFetch : fetch;
            const response = await fetchFn(`${this.baseUrl}/public/reading-lists/${this.shareToken}/articles`);
            
            console.log('Public list response status:', response.status);
            
            if (!response.ok) {
                if (response.status === 404) {
                    console.log('Reading list not found or private');
                    this.showPrivateMessage('This reading list is private or no longer available');
                    return;
                }
                const errorText = await response.text();
                console.error('Error response:', errorText);
                throw new Error(`Failed to load shared reading list: ${response.status}`);
            }

            this.listData = await response.json();
            this.articles = this.listData.articles || [];
            
            console.log('Loaded shared reading list:', this.listData);
            this.renderPublicView();
            
        } catch (error) {
            console.error('Error loading shared reading list:', error);
            this.showPrivateMessage('Unable to load reading list. Please try again later.');
        } finally {
            this.hideLoading();
        }
    }

    renderPublicView() {
        if (!this.listData) {
            this.showPrivateMessage('Reading list not found');
            return;
        }

        // Update header with list information
        this.updateHeader();

        // Render articles
        this.renderArticles();
    }

    updateHeader() {
        const listNameEl = document.getElementById('listName');
        const ownerNameEl = document.getElementById('ownerName');
        const sharedDateEl = document.getElementById('sharedDate');
        const listTitleEl = document.getElementById('listTitle');
        
        console.log('Updating header with list data:', this.listData);
        
        if (listNameEl) {
            listNameEl.textContent = this.listData.name || 'Untitled List';
        }
        
        if (ownerNameEl) {
            const ownerUsername = this.listData.ownerUsername || 'Unknown User';
            console.log('Setting owner username:', ownerUsername);
            ownerNameEl.textContent = ownerUsername;
        }
        
        if (sharedDateEl && this.listData.sharedAt) {
            const sharedDate = new Date(this.listData.sharedAt).toLocaleDateString();
            sharedDateEl.textContent = ` • Shared ${sharedDate}`;
        }
        
        // Apply color theme to header
        if (listTitleEl && this.listData.colorTheme) {
            listTitleEl.style.borderLeft = `4px solid ${this.listData.colorTheme}`;
            listTitleEl.style.paddingLeft = '1rem';
        }
        
        // Update page title
        document.title = `${this.listData.name} - Shared Reading List - The Crux`;
    }

    renderArticles() {
        const container = document.getElementById('articlesContainer');
        const emptyState = document.getElementById('emptyState');
        const privateState = document.getElementById('privateState');
        
        if (!container || !emptyState) return;

        // Hide private state
        privateState.style.display = 'none';

        if (this.articles.length === 0) {
            container.style.display = 'none';
            container.innerHTML = '';
            emptyState.style.display = 'block';
            return;
        }

        // Non-empty state
        emptyState.style.display = 'none';
        container.style.display = 'grid';

        // Rebuild container content with public article cards
        container.innerHTML = '';
        this.articles.forEach(article => {
            const articleCard = this.createPublicArticleCard(article);
            container.appendChild(articleCard);
        });
    }

    createPublicArticleCard(article) {
        const card = document.createElement('div');
        card.className = 'article-card glass-card public-article-card';
        card.dataset.articleId = article.id;

        // Date handling
        const publishDate = article.publishDate ? new Date(article.publishDate).toLocaleDateString() : '';
        
        // Source handling
        const source = article.source || 'Unknown Source';
        const displaySource = source === 'The Guardian (Full Article)' ? 'The Guardian' : source;
        
        // Content handling
        const content = article.content || article.summary || article.description || '';
        const cleanContent = this.stripHtml(content).substring(0, 300) + (content.length > 300 ? '...' : '');
        
        // Image handling
        let imageHtml = '';
        if (article.imageUrl) {
            imageHtml = `
                <div class="article-media">
                    <img src="${this.escapeHtml(article.imageUrl)}" 
                         alt="${this.escapeHtml(article.title)}" 
                         class="article-image" 
                         onerror="this.style.display='none'">
                </div>`;
        } else {
            imageHtml = `
                <div class="article-media">
                    <div class="article-image-placeholder">
                        <span>📰 ${this.escapeHtml(displaySource)}</span>
                    </div>
                </div>`;
        }

        card.innerHTML = `
            ${imageHtml}
            <div class="article-content-wrapper">
                <h3 class="article-title">${this.escapeHtml(article.title)}</h3>
                <div class="article-meta">
                    <span class="article-source">${this.escapeHtml(displaySource)}</span>
                    <span class="meta-right"><span class="article-date">${publishDate}</span></span>
                </div>
                <p class="article-summary">${this.escapeHtml(cleanContent)}</p>
                <div class="article-actions public-actions">
                    <a href="${article.url || '#'}" target="_blank" class="btn btn-primary btn-small glass-card">
                        <i class="fas fa-external-link-alt"></i>
                        Read Full Article
                    </a>
                </div>
            </div>
        `;

        return card;
    }

    showPrivateMessage(message) {
        const container = document.getElementById('articlesContainer');
        const emptyState = document.getElementById('emptyState');
        const privateState = document.getElementById('privateState');
        
        if (container) container.style.display = 'none';
        if (emptyState) emptyState.style.display = 'none';
        if (privateState) {
            privateState.style.display = 'block';
            const messageEl = privateState.querySelector('p');
            if (messageEl) {
                messageEl.textContent = message;
            }
        }

        // Update page title
        document.title = 'Reading List Not Available - The Crux';
    }

    stripHtml(html) {
        if (!html || typeof html !== 'string') return '';
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = html;
        return (tempDiv.textContent || tempDiv.innerText || '').trim();
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    showLoading() {
        const overlay = document.getElementById('loadingOverlay');
        if (overlay) {
            overlay.style.display = 'flex';
        }
    }

    hideLoading() {
        const overlay = document.getElementById('loadingOverlay');
        if (overlay) {
            overlay.style.display = 'none';
        }
    }
}

// Initialize when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.sharedReadingListView = new SharedReadingListView();
});