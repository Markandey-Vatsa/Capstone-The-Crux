// Bookmarks page functionality
class BookmarksManager {
    constructor() {
    if(!window.API_BASE_URL){ window.API_BASE_URL = 'http://localhost:8080/api'; }
    this.baseUrl = window.API_BASE_URL;
        this.bookmarkedArticles = [];
        this.currentArticle = null;
        this.init();
    }

    init() {
        if (!authUtils.requireAuth()) {
            return;
        }

    // First hide loading overlay to prevent blur issues
    uiUtils.hideLoading();
        
        this.loadBookmarkedArticles();
        this.setupEventListeners();
    }

    setupEventListeners() {
        // Logout button
        const logoutBtn = document.getElementById('logoutBtn');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', () => authUtils.logout());
        }

        // Refresh button
        const refreshBtn = document.getElementById('refreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', this.loadBookmarkedArticles.bind(this));
        }

        // Modal bookmark button
        const modalBookmarkBtn = document.getElementById('modalBookmarkBtn');
        if (modalBookmarkBtn) {
            modalBookmarkBtn.addEventListener('click', this.toggleCurrentArticleBookmark.bind(this));
        }

        // Modal bias analysis button
        const modalBiasBtn = document.getElementById('modalBiasBtn');
        if (modalBiasBtn) {
            modalBiasBtn.addEventListener('click', this.analyzeCurrentArticleBias.bind(this));
        }

        // Bias analysis modal event listeners
        const biasModal = document.getElementById('bias-analysis-modal');
        if (biasModal) {
            // Close modal when clicking outside
            biasModal.addEventListener('click', (e) => {
                if (e.target === biasModal) {
                    closeBiasModal();
                }
            });
        }

        // Close modal with Escape key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                const biasModal = document.getElementById('bias-analysis-modal');
                if (biasModal && biasModal.style.display === 'flex') {
                    closeBiasModal();
                }
            }
        });
    }

    async loadBookmarkedArticles() {
        try {
            uiUtils.showLoading();
            
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/user/bookmarks`, {
                method: 'GET'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                throw new Error(`Failed to load bookmarks: ${response.status}`);
            }

            this.bookmarkedArticles = await response.json();
            if (this.bookmarkedArticles.length) {
                console.log('First bookmarked article object:', this.bookmarkedArticles[0]);
            }
            this.renderBookmarks(this.bookmarkedArticles);
            
        } catch (error) {
            console.error('Error loading bookmarks:', error);
            uiUtils.showModal('errorModal', 'Failed to load bookmarks. Please try again.');
        } finally {
            uiUtils.hideLoading();
        }
    }

    // Unified renderer for bookmarks state
    renderBookmarks(articles) {
        const bookmarksList = document.getElementById('bookmarksList');
        const emptyState = document.getElementById('emptyState');
        const bookmarkStats = document.getElementById('bookmarkStats');
        if (!bookmarksList || !emptyState || !bookmarkStats) return;

        const count = Array.isArray(articles) ? articles.length : 0;
        if (count === 0) {
            bookmarkStats.textContent = 'No bookmarked articles';
            // Hide list, show empty state and clear any residual cards
            bookmarksList.style.display = 'none';
            bookmarksList.innerHTML = '';
            emptyState.style.display = 'block';
            return;
        }

        // Non-empty state
        bookmarkStats.textContent = `${count} saved article${count !== 1 ? 's' : ''}`;
        emptyState.style.display = 'none';
        bookmarksList.style.display = 'block';

        // Rebuild list content
        bookmarksList.innerHTML = '';
        articles.forEach(article => bookmarksList.appendChild(this.createArticleCard(article)));

        // Event delegation (attach once)
        if (!bookmarksList._delegated) {
            bookmarksList.addEventListener('click', (e) => {
                const btn = e.target.closest('button');
                if (!btn) return;
                const id = btn.dataset.id;
                if (!id) return;
                const card = btn.closest('.article-card');
                if (btn.classList.contains('read-full-btn')) {
                    this.openArticleModal(id);
                } else if (btn.classList.contains('analyze-btn')) {
                    this.analyzeArticleBias(id, card);
                } else if (btn.classList.contains('remove-btn')) {
                    this.toggleBookmark(id);
                } else if (btn.classList.contains('export-btn')) {
                    this.exportArticle(id);
                }
            });
            bookmarksList._delegated = true;
        }
    }

    createArticleCard(article) {
        const card = document.createElement('div');
        card.className = 'article-card glass-card';

        // Date handling (reuse publishDate)
        const rawDate = article.publishDate || article.publishedDate;
        const publishDate = rawDate ? new Date(rawDate).toLocaleDateString() : '';

        // Summary logic consistent with news.js
        const source = article.source || 'Unknown Source';
        const contentBase = article.summary || article.description || article.content || '';
        const cleanSummary = this.stripHtml(contentBase).substring(0, 300) + (contentBase.length > 300 ? '...' : '');

        // Image (simple: trust imageUrl field)
        let imageHtml = '';
        if (article.imageUrl) {
            imageHtml = `
                <img src="${article.imageUrl}" alt="${this.escapeHtml(article.title)}" class="article-image" onerror="this.style.display='none'">`;
        } else {
            imageHtml = `<div class="article-image-placeholder"><span>📰 ${this.escapeHtml(source)}</span></div>`;
        }

        // Bias signal indicator removed

    card.innerHTML = `
            ${imageHtml}
            <div class="article-content-wrapper">
                <h3 class="article-title">${this.escapeHtml(article.title)}</h3>
                <div class="article-meta">
                    <span class="article-source">${this.escapeHtml(source)}</span>
                    <span class="meta-right"><span class="article-date">${publishDate}</span></span>
                </div>
                <p class="article-summary">${this.escapeHtml(cleanSummary)}</p>
                <div class="article-actions">
            <button class="btn btn-primary btn-small glass-card analyze-btn" data-id="${article.id}"><span class="btn-label">Analyze Bias</span></button>
                    <button class="btn btn-secondary btn-small glass-card remove-btn" data-id="${article.id}"><i class="fas fa-bookmark"></i> Remove</button>
                    <button class="btn btn-secondary btn-small glass-card export-btn" data-id="${article.id}"><i class="fas fa-download"></i> Export</button>
                    <a href="${article.url || '#'}" target="_blank" class="btn btn-secondary btn-small glass-card">Read Full</a>
                </div>
            </div>`;
        return card;
    }

    // Utility: strip HTML (mirrors news.js safeguard)
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

    async toggleBookmark(articleId) {
        try {
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/user/bookmarks/${articleId}`, {
                method: 'POST'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                throw new Error(`Failed to toggle bookmark: ${response.status}`);
            }

            const result = await response.json();
            
            // If article was unbookmarked, remove it from the list
            if (!result.bookmarked) {
                this.bookmarkedArticles = this.bookmarkedArticles.filter(article => article.id !== articleId);
                this.renderBookmarks(this.bookmarkedArticles);
            }
            
        } catch (error) {
            console.error('Error toggling bookmark:', error);
            uiUtils.showModal('errorModal', 'Failed to update bookmark. Please try again.');
        }
    }

    exportArticle(articleId) {
        try {
            console.log('Exporting bookmarked article:', articleId);
            
            // Find the article data from bookmarkedArticles array
            const article = this.bookmarkedArticles.find(a => a.id === articleId);
            if (!article) {
                console.error('Bookmarked article not found for export:', articleId);
                uiUtils.showModal('errorModal', 'Article not found for export.');
                return;
            }

            // Show export modal with article data
            if (window.exportModal) {
                window.exportModal.show(article);
            } else {
                console.error('Export modal not available');
                uiUtils.showModal('errorModal', 'Export functionality not available.');
            }
            
        } catch (error) {
            console.error('Error exporting bookmarked article:', error);
            uiUtils.showModal('errorModal', 'Failed to export article. Please try again.');
        }
    }

    openArticleModal(articleId) {
        const article = this.bookmarkedArticles.find(a => a.id === articleId);

            // Rate limit friendly handling
            if (data && data.error === 'RATE_LIMIT_EXCEEDED') {
                if (aiContainer) {
                    aiContainer.innerHTML = `
                        <div class="alert alert-warning">
                            <h4>Daily Analysis Limit Reached</h4>
                            <p>${this.escapeHtml(data.message || 'The daily analysis quota has been reached. Please try again tomorrow.')}</p>
                        </div>`;
                }
            } else if (data && (data.biasLevel || data.primaryBias || data.detailedExplanation)) {
                // New structured schema path
                if (aiContainer) aiContainer.innerHTML = this.buildStructuredBiasHtml(data);
            } else {
                // Legacy schema path
                if (aiContainer) {
                    aiContainer.innerHTML = this.buildUnifiedBiasHtml(data);
                }
            }

    }

    buildStructuredBiasHtml(data){
        const sanitize = (t) => this.escapeHtml(t || '');
        const biasLevel = data.biasLevel || 'Neutral';
        const confidence = data.confidence || 'Medium';
        const reasonForBias = data.reasonForBias || 'No specific reason provided.';
        const xaiJustification = data.xaiJustification || 'AI reasoning not available.';
        const missingContext = Array.isArray(data.missingContext) ? data.missingContext.slice(0, 3) : [];
        const balancedPerspective = data.balancedPerspective || 'No balanced perspective provided.';
        const selfReflection = data.selfReflection || 'No self-assessment available.';
        
        const levelClass = biasLevel.toLowerCase();
        const confidenceClass = confidence.toLowerCase();
        
        // Missing context list
        const missingHtml = missingContext.length > 0 
            ? `<ul class="missing-context-list">${missingContext.map(item => `<li>${sanitize(item)}</li>`).join('')}</ul>`
            : '<p>No missing context identified.</p>';
        
        return `
            <div class="xai-bias-analysis">
                <!-- Analysis Header -->
                <div class="analysis-header">
                    <div class="bias-level-container">
                        <span class="bias-level ${levelClass}">${sanitize(biasLevel)} Bias</span>
                        <span class="confidence-badge ${confidenceClass}">${sanitize(confidence)} Confidence</span>
                    </div>
                </div>

                <!-- Why This Rating -->
                <div class="analysis-section">
                    <h4>📋 Why This Rating?</h4>
                    <p class="concise-text">${sanitize(reasonForBias)}</p>
                </div>

                <!-- XAI Section -->
                <div class="analysis-section xai-highlight">
                    <h4>🧠 AI Reasoning Process (XAI)</h4>
                    <p class="concise-text xai-explanation">${sanitize(xaiJustification)}</p>
                    <small class="xai-note">This shows how the AI made its decision - a key Explainable AI feature</small>
                </div>

                <!-- Missing Context -->
                <div class="analysis-section">
                    <h4>❓ Missing Context</h4>
                    ${missingHtml}
                </div>

                <!-- Balanced Perspective -->
                <div class="analysis-section">
                    <h4>⚖️ More Balanced Perspective</h4>
                    <p class="concise-text">${sanitize(balancedPerspective)}</p>
                </div>

                <!-- AI Self-Assessment -->
                <div class="analysis-section">
                    <h4>🤖 AI Self-Assessment</h4>
                    <p class="concise-text">${sanitize(selfReflection)}</p>
                </div>
            </div>`;
    }

    async toggleCurrentArticleBookmark() {
        if (!this.currentArticle) return;
        
    await this.toggleBookmark(this.currentArticle.id);
        closeArticleModal();
    }

    async analyzeArticleBias(articleId, cardEl) {
        const article = this.bookmarkedArticles.find(a => a.id === articleId);
        if (!article) return;
        this.currentArticle = article;
        this.activeLoadingCard = cardEl;
    this.showCardLoading(cardEl, 'Analyzing bias...');
    const btn = cardEl.querySelector('.analyze-btn');
    if (btn) { btn.classList.add('is-loading'); const label = btn.querySelector('.btn-label'); if (label) label.textContent='Analyzing…'; }
        await this.analyzeCurrentArticleBias();
    }

    async analyzeCurrentArticleBias() {
        if (!this.currentArticle) return;
        const id = this.currentArticle.id;
        const url = `${this.baseUrl}/news/${id}/bias`;
        const modal = document.getElementById('bias-analysis-modal');
        const loadingEl = document.getElementById('bias-analysis-loading');
        const contentEl = document.getElementById('bias-analysis-content');
        const originalContainer = document.getElementById('original-article-content');
        const aiContainer = document.getElementById('ai-analysis-content');
        try {
            // Open modal and show loading
            if (modal) {
                modal.style.display = 'flex';
                modal.classList.add('active');
            }
            if (loadingEl) loadingEl.classList.remove('hidden');
            if (contentEl) contentEl.classList.add('hidden');
            // Populate original article
            if (originalContainer) {
                const publishDate = this.currentArticle.publishDate ? new Date(this.currentArticle.publishDate).toLocaleDateString() : '';
                originalContainer.innerHTML = `
                    <h4>${this.escapeHtml(this.currentArticle.title)}</h4>
                    <div class="article-meta"><span class="article-source">${this.escapeHtml(this.currentArticle.source||'')}</span><span class="article-date">${publishDate}</span></div>
                    <div class="article-full-content">${this.escapeHtml(this.stripHtml(this.currentArticle.content || this.currentArticle.description || ''))}</div>`;
            }
            const controller = new AbortController();
            const timeoutId = setTimeout(()=>controller.abort(),45000);
            const response = await authUtils.makeAuthenticatedRequest(url, { method:'POST', signal: controller.signal });
            clearTimeout(timeoutId);
            if (!response) return;
            const raw = await response.text();
            if (!response.ok) throw new Error(`Status ${response.status}: ${raw}`);
            let data;
            try { 
                data = JSON.parse(raw); 
                console.log('Bookmarks bias analysis response:', data);
            } catch { 
                console.log('Failed to parse bias response, using fallback:', raw);
                data = { quick_summary: { bias_summary: raw } }; 
            }
            if (Array.isArray(data)) data = data[0] || {};
            // Reuse formatter similar to dashboard
            if (this.currentArticle && this.currentArticle.category) {
                const modalEl = document.getElementById('bias-analysis-modal');
                if (modalEl) {
                    modalEl.className = modalEl.className.replace(/category-[a-z]+/gi, '').trim();
                    modalEl.classList.add('category-' + this.currentArticle.category.toLowerCase());
                }
            }
            if (aiContainer) {
                // Check for XAI-enhanced bias analysis format
                const xaiKeysPresent = data && (
                    data.biasLevel ||
                    data.confidence ||
                    data.reasonForBias ||
                    data.xaiJustification ||
                    Array.isArray(data.missingContext) ||
                    data.balancedPerspective ||
                    data.selfReflection
                );
                
                // Check for legacy structured format
                const legacyKeysPresent = data && (
                    data.primaryBias ||
                    data.alternativeView ||
                    data.detailedExplanation
                );
                
                if (xaiKeysPresent) {
                    aiContainer.innerHTML = this.buildStructuredBiasHtml(data);
                } else if (legacyKeysPresent) {
                    aiContainer.innerHTML = this.buildLegacyStructuredBiasHtml(data);
                } else {
                    aiContainer.innerHTML = this.buildUnifiedBiasHtml(data);
                }
            }
            if (loadingEl) loadingEl.classList.add('hidden');
            if (contentEl) contentEl.classList.remove('hidden');
        } catch(err) {
            console.error('Bias analysis failed:', err);
            if (aiContainer) aiContainer.innerHTML = `<p class="alert alert-error">${this.escapeHtml(err.message)}</p>`;
            if (loadingEl) loadingEl.classList.add('hidden');
            if (contentEl) contentEl.classList.remove('hidden');
        } finally {
            if (this.activeLoadingCard) this.hideCardLoading(this.activeLoadingCard);
            // restore analyze button
            if (this.activeLoadingCard) {
                const btn = this.activeLoadingCard.querySelector('.analyze-btn');
                if (btn) { btn.classList.remove('is-loading'); const label = btn.querySelector('.btn-label'); if (label) label.textContent='Analyze Bias'; }
            }
            this.activeLoadingCard = null;
        }
    }

    showCardLoading(card, message) {
        if (!card) return;
        card.classList.add('analyzing');
        let overlay = card.querySelector('.card-loading-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.className = 'card-loading-overlay';
            overlay.innerHTML = `<div class="inner"><div class="spinner small"></div><span>${message || 'Loading...'}</span></div>`;
            card.appendChild(overlay);
        } else {
            overlay.querySelector('span').textContent = message || 'Loading...';
            overlay.style.display = 'flex';
        }
    }

    hideCardLoading(card) {
        if (!card) return;
        card.classList.remove('analyzing');
        const overlay = card.querySelector('.card-loading-overlay');
        if (overlay) overlay.remove();
    }

    // Legacy displayBiasAnalysis no longer used; kept for backward compatibility
    displayBiasAnalysis() {}

    buildLegacyStructuredBiasHtml(data){
        const sanitize = (t)=>this.escapeHtml(t||'');
        const biasLevel = data.biasLevel || 'Neutral';
        const levelClass = biasLevel.toLowerCase();
        const missing = Array.isArray(data.missingContext)? data.missingContext: [];
        const missingHtml = missing.length ? `<ul>${missing.map(i=>`<li>${sanitize(i)}</li>`).join('')}</ul>` : '<p>None identified.</p>';
        return `
            <div class="analysis-section quick-summary">
                <h4>🎯 Quick Summary</h4>
                <div class="bias-overview">
                    <p><strong>Bias Level:</strong> <span class="bias-level ${levelClass}">${sanitize(biasLevel)}</span></p>
                    <p><strong>Reason for Rating:</strong> ${sanitize(data.primaryBias)}</p>
                    <p><strong>Alternative View:</strong> ${sanitize(data.alternativeView)}</p>
                </div>
            </div>
            <div class="analysis-section detailed-analysis collapsible" id="bm-structured">
                <h4 class="collapsible-header" onclick="(function(){const wrap=document.getElementById('bm-structured');const c=document.getElementById('bm-structured-content');const a=document.getElementById('bm-structured-arrow');if(!wrap||!c||!a)return;const open=wrap.classList.toggle('open');if(open){c.style.maxHeight=c.scrollHeight+'px';c.setAttribute('aria-hidden','false');a.textContent='▲';}else{c.style.maxHeight='0px';c.setAttribute('aria-hidden','true');a.textContent='▼';}})()"><span>📊 Detailed Analysis</span><span id="bm-structured-arrow">▼</span></h4>
                <div class="collapsible-content" id="bm-structured-content" aria-hidden="true" style="max-height:0">
                    <div class="detail-subsection"><p><strong>Explanation:</strong></p><p>${sanitize(data.detailedExplanation)}</p></div>
                    <div class="detail-subsection"><p><strong>Missing Context:</strong></p>${missingHtml}</div>
                    <div class="detail-subsection"><p><strong>Balanced Perspective:</strong></p><p>${sanitize(data.balancedPerspective)}</p></div>
                </div>
            </div>`;
    }

    buildUnifiedBiasHtml(analysis){
        const qs = analysis.quick_summary || {};
        const da = analysis.detailed_analysis || {};
        const biasLevel = (qs.bias_level || 'Unknown').toLowerCase();
        const quickHtml = `
            <div class="analysis-section quick-summary">
                <h4>🎯 Quick Summary</h4>
                <div class="bias-overview">
                    <p><strong>Bias Level:</strong> <span class="bias-level ${biasLevel}">${this.escapeHtml(qs.bias_level||'Unknown')}</span></p>
                    <p><strong>Primary Bias:</strong> ${this.escapeHtml(qs.bias_summary||'')}</p>
                    <p><strong>Alternative View:</strong> ${this.escapeHtml(qs.counter_narrative||'')}</p>
                </div>
            </div>`;
        // Detailed
        let detailInner = '';
        if (da.explanation) detailInner += `<div class="detail-subsection"><p><strong>Explanation:</strong></p><p>${this.escapeHtml(da.explanation)}</p></div>`;
        if (Array.isArray(da.missing_context) && da.missing_context.length) detailInner += `<div class="detail-subsection"><p><strong>Missing Context:</strong></p><ul>${da.missing_context.map(c=>`<li>${this.escapeHtml(c)}</li>`).join('')}</ul></div>`;
        if (da.balanced_conclusion) detailInner += `<div class="detail-subsection"><p><strong>Balanced Perspective:</strong></p><p>${this.escapeHtml(da.balanced_conclusion)}</p></div>`;
        const detailedHtml = `
            <div class="analysis-section detailed-analysis">
                <h4 onclick="(function(){const c=document.getElementById('bm-detailed');if(!c)return;if(c.style.display==='none'){c.style.display='block';document.getElementById('bm-toggle').textContent='▲';}else{c.style.display='none';document.getElementById('bm-toggle').textContent='▼';}})()" style="cursor:pointer;user-select:none;">📊 Detailed Analysis <span id="bm-toggle">▼</span></h4>
                <div id="bm-detailed" style="display:none;">${detailInner||'<p>No detailed analysis available.</p>'}</div>
            </div>`;
        return quickHtml + detailedHtml;
    }

    formatQuickSummary(data) {
        if (!data) return '<p>No summary available.</p>';
        if (typeof data === 'string') {
            return `<p>${this.escapeHtml(data)}</p>`;
        }
        // If object with fields
        let html = '<ul class="bias-qs-list">';
        for (const [k,v] of Object.entries(data)) {
            if (v == null) continue;
            html += `<li><strong>${this.escapeHtml(k.replace(/_/g,' '))}:</strong> ${this.escapeHtml(String(v))}</li>`;
        }
        html += '</ul>';
        return html;
    }

    formatDetailedAnalysis(data) {
        if (!data) return 'No detailed analysis available.';
        if (typeof data === 'string') return this.escapeHtml(data).replace(/\n/g,'<br>');
        // If object, format nested keys
        let sections = '';
        for (const [k,v] of Object.entries(data)) {
            if (!v) continue;
            if (Array.isArray(v)) {
                sections += `<div class="da-section"><h5>${this.escapeHtml(k.replace(/_/g,' '))}</h5><ul>` + v.map(item=>`<li>${this.escapeHtml(String(item))}</li>`).join('') + '</ul></div>';
            } else if (typeof v === 'object') {
                sections += `<div class="da-section"><h5>${this.escapeHtml(k.replace(/_/g,' '))}</h5><pre>${this.escapeHtml(JSON.stringify(v, null, 2))}</pre></div>`;
            } else {
                sections += `<div class="da-section"><h5>${this.escapeHtml(k.replace(/_/g,' '))}</h5><p>${this.escapeHtml(String(v))}</p></div>`;
            }
        }
        return sections || 'No detailed analysis available.';
    }

    // Utility escape reused in new helpers
    escapeHtml(text) { 
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    showBiasError(message) {
        const biasContent = document.getElementById('biasContent');
        biasContent.innerHTML = `
            <div class="error-state">
                <i class="fas fa-exclamation-triangle"></i>
                <p>${message}</p>
            </div>
        `;
    }

}

// Global functions for modal management
function closeArticleModal() {
    const modal = document.getElementById('articleModal');
    if (modal) {
        modal.style.display = 'none';
    }
}

function closeBiasModal() {
    const modal = document.getElementById('bias-analysis-modal');
    if (modal) {
        modal.style.display = 'none';
        modal.classList.remove('active');
    }
    // Abort any in-flight analysis (bookmarks page local controller not globally stored, so rely on stored controller)
    if (window._bmBiasAbort) { try { window._bmBiasAbort.abort(); } catch(e){} }
    const loadingEl = document.getElementById('bias-analysis-loading');
    const contentEl = document.getElementById('bias-analysis-content');
    if (loadingEl) loadingEl.classList.add('hidden');
    if (contentEl) contentEl.classList.remove('hidden');
    const original = document.getElementById('original-article-content');
    const ai = document.getElementById('ai-analysis-content');
    if (original) original.innerHTML='';
    if (ai) ai.innerHTML='';
}

// Global instance for use in HTML onclick handlers
let bookmarksManager;

// Initialize bookmarks manager when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    bookmarksManager = new BookmarksManager();
});

// Handle browser back/forward navigation
window.addEventListener('popstate', () => {
    // Reload bookmarks if user navigates back to this page
    if (bookmarksManager) {
        bookmarksManager.loadBookmarkedArticles();
    }
});
