// Reading List View page functionality
class ReadingListViewManager {
    constructor() {
        if (!window.API_BASE_URL) {
            window.API_BASE_URL = 'http://localhost:8080/api';
        }
        this.baseUrl = window.API_BASE_URL;
        this.currentList = null;
        this.articles = [];
        this.currentRemovingArticle = null;
        this.currentAnalysisArticle = null;
        this.init();
    }

    init() {
        if (!authUtils.requireAuth()) {
            return;
        }

        // First hide loading overlay to prevent blur issues
        uiUtils.hideLoading();

        this.setupEventListeners();

        // Get list ID from URL parameters
        const urlParams = new URLSearchParams(window.location.search);
        let listId = urlParams.get('id');
        if (listId) {
            listId = listId.trim();
        }

        console.log('Reading List View - URL:', window.location.href);
        console.log('Reading List View - List ID from URL:', listId);

        if (listId) {
            this.loadReadingList(listId);
        } else {
            console.warn('No reading list ID found in URL; attempting fallback to first available list.');
            this.handleMissingListId();
        }
    }

    setupEventListeners() {
        // Close button
        const closeBtn = document.getElementById('closeBtn');
        if (closeBtn) {
            closeBtn.addEventListener('click', () => {
                window.location.href = 'reading-lists.html';
            });
        }

        // Share button
        const shareBtn = document.getElementById('shareBtn');
        if (shareBtn) {
            shareBtn.addEventListener('click', () => {
                this.showShareModal();
            });
        }

        // Share modal event listeners
        const saveShareBtn = document.getElementById('saveShareBtn');
        if (saveShareBtn) {
            saveShareBtn.addEventListener('click', this.handleShareSave.bind(this));
        }

        const copyUrlBtn = document.getElementById('copyUrlBtn');
        if (copyUrlBtn) {
            copyUrlBtn.addEventListener('click', this.copyShareUrl.bind(this));
        }

        // Privacy radio buttons - will be set up when modal is shown
        // (since the modal elements may not exist when this init runs)

        // Remove article confirmation
        const confirmRemoveBtn = document.getElementById('confirmRemoveBtn');
        if (confirmRemoveBtn) {
            confirmRemoveBtn.addEventListener('click', this.confirmRemoveArticle.bind(this));
        }

        // Bias analysis modal event listeners
        const biasModal = document.getElementById('bias-analysis-modal');
        if (biasModal) {
            // Close modal when clicking outside
            biasModal.addEventListener('click', (e) => {
                if (e.target === biasModal) {
                    this.closeBiasModal();
                }
            });
        }

        // Close modals on escape key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeAllModals();
            }
        });
    }

    async handleMissingListId() {
        try {
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists`, {
                method: 'GET'
            });

            if (response && response.ok) {
                const lists = await response.json();
                if (Array.isArray(lists)) {
                    const fallback = lists.find(list => this.resolveListId(list));
                    if (fallback) {
                        const fallbackId = this.resolveListId(fallback);
                        const newUrl = `${window.location.pathname}?id=${encodeURIComponent(fallbackId)}`;
                        window.history.replaceState(null, '', newUrl);
                        await this.loadReadingList(fallbackId);
                        return;
                    }
                }
            }
        } catch (error) {
            console.error('Error resolving fallback reading list:', error);
        }

        uiUtils.showModal('errorModal', 'No reading list specified. Please navigate from the Reading Lists page.');
    }

    async loadReadingList(listId) {
        const safeListId = (listId || '').trim();
        if (!safeListId) {
            console.warn('Attempted to load reading list with invalid id:', listId);
            await this.handleMissingListId();
            return;
        }

        try {
            uiUtils.showLoading();

            // Load articles for this reading list
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${encodeURIComponent(safeListId)}/articles`, {
                method: 'GET'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                if (response.status === 404) {
                    uiUtils.showModal('errorModal', 'Reading list not found');
                    return;
                }
                throw new Error(`Failed to load reading list: ${response.status}`);
            }

            this.articles = await response.json();

            // Also get the reading list details for the title
            await this.loadReadingListDetails(safeListId);

            console.log('Loaded reading list articles:', this.articles);
            this.renderArticles();

        } catch (error) {
            console.error('Error loading reading list:', error);
            uiUtils.showModal('errorModal', 'Failed to load reading list. Please try again.');
        } finally {
            uiUtils.hideLoading();
        }
    }

    async loadReadingListDetails(listId) {
        try {
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists`, {
                method: 'GET'
            });

            if (!response) {
                return;
            }

            if (response.ok) {
                const lists = await response.json();
                let matchedList = null;

                if (Array.isArray(lists)) {
                    for (const list of lists) {
                        const resolvedId = this.resolveListId(list);
                        if (resolvedId === listId) {
                            matchedList = { ...list, id: resolvedId };
                            break;
                        }
                    }
                }

                if (matchedList) {
                    // Initialize sharing fields if not present
                    if (matchedList.isPublic === undefined) {
                        matchedList.isPublic = false;
                    }
                    if (matchedList.shareToken === undefined) {
                        matchedList.shareToken = null;
                    }
                    if (matchedList.sharedAt === undefined) {
                        matchedList.sharedAt = null;
                    }

                    this.currentList = matchedList;
                    console.log('Loaded reading list details:', this.currentList);
                    this.updateHeader();
                } else {
                    console.warn('Reading list not found in user lists:', listId);
                }
            } else {
                console.error('Failed to load reading lists:', response.status);
            }
        } catch (error) {
            console.error('Error loading reading list details:', error);
        }
    }

    updateHeader() {
        const listNameEl = document.getElementById('listName');
        const listStatsEl = document.getElementById('listStats');
        const listTitleEl = document.getElementById('listTitle');

        if (this.currentList) {
            if (listNameEl) {
                listNameEl.textContent = this.currentList.name;
            }

            if (listStatsEl) {
                const count = this.articles.length;
                listStatsEl.textContent = `${count} article${count !== 1 ? 's' : ''}`;
            }

            // Apply color theme to header
            if (listTitleEl && this.currentList.colorTheme) {
                listTitleEl.style.borderLeft = `4px solid ${this.currentList.colorTheme}`;
                listTitleEl.style.paddingLeft = '1rem';
            }

            // Update page title
            document.title = `${this.currentList.name} - The Crux`;
        }
    }

    renderArticles() {
        const container = document.getElementById('articlesContainer');
        const emptyState = document.getElementById('emptyState');

        if (!container || !emptyState) return;

        if (this.articles.length === 0) {
            container.style.display = 'none';
            container.innerHTML = '';
            emptyState.style.display = 'block';
            return;
        }

        // Non-empty state
        emptyState.style.display = 'none';
        container.style.display = 'grid';

        // Rebuild container content
        container.innerHTML = '';
        this.articles.forEach((article, index) => {
            const articleCard = this.createArticleCard(article);

            // Update button states based on position
            const moveUpBtn = articleCard.querySelector('.move-up-btn');
            const moveDownBtn = articleCard.querySelector('.move-down-btn');

            if (moveUpBtn) {
                moveUpBtn.disabled = index === 0;
                moveUpBtn.style.opacity = index === 0 ? '0.5' : '1';
            }

            if (moveDownBtn) {
                moveDownBtn.disabled = index === this.articles.length - 1;
                moveDownBtn.style.opacity = index === this.articles.length - 1 ? '0.5' : '1';
            }

            container.appendChild(articleCard);
        });
    }

    createArticleCard(article) {
        const card = document.createElement('div');
        card.className = 'article-card glass-card';
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
                <div class="article-actions">
                    <div class="article-reorder-controls">
                        <button class="btn btn-small btn-secondary glass-card move-up-btn" data-article-id="${article.id}" title="Move up">
                            <i class="fas fa-chevron-up"></i>
                        </button>
                        <button class="btn btn-small btn-secondary glass-card move-down-btn" data-article-id="${article.id}" title="Move down">
                            <i class="fas fa-chevron-down"></i>
                        </button>
                    </div>
                    <button class="btn btn-primary btn-small glass-card analyze-bias-btn" data-article-id="${article.id}">
                        <span class="btn-label">Analyze Bias</span>
                    </button>
                    <button class="btn btn-danger btn-small glass-card remove-btn" data-article-id="${article.id}">
                        <i class="fas fa-times"></i>
                        <span class="btn-text">Remove</span>
                    </button>
                    <button class="btn btn-secondary btn-small glass-card export-btn" data-article-id="${article.id}">
                        <i class="fas fa-download"></i>
                        <span class="btn-text">Export</span>
                    </button>
                    <a href="${article.url || '#'}" target="_blank" class="btn btn-secondary btn-small glass-card">
                        Read Full
                    </a>
                </div>
            </div>
        `;

        // Add event listeners
        const analyzeBtn = card.querySelector('.analyze-bias-btn');
        const removeBtn = card.querySelector('.remove-btn');
        const exportBtn = card.querySelector('.export-btn');
        const moveUpBtn = card.querySelector('.move-up-btn');
        const moveDownBtn = card.querySelector('.move-down-btn');

        if (analyzeBtn) {
            analyzeBtn.addEventListener('click', () => this.analyzeArticleBias(article));
        }

        if (removeBtn) {
            removeBtn.addEventListener('click', () => this.showRemoveConfirmation(article));
        }

        if (exportBtn) {
            exportBtn.addEventListener('click', () => this.exportArticle(article));
        }

        if (moveUpBtn) {
            moveUpBtn.addEventListener('click', () => this.moveArticle(article.id, 'up'));
        }

        if (moveDownBtn) {
            moveDownBtn.addEventListener('click', () => this.moveArticle(article.id, 'down'));
        }

        return card;
    }

    showRemoveConfirmation(article) {
        this.currentRemovingArticle = article;
        this.showModal('removeModal');
    }

    async confirmRemoveArticle() {
        if (!this.currentRemovingArticle || !this.currentList) return;

        const currentListId = this.resolveListId(this.currentList);
        if (!currentListId) {
            uiUtils.showModal('errorModal', 'Reading list information is missing. Please reopen the list.');
            return;
        }

        try {
            const confirmBtn = document.getElementById('confirmRemoveBtn');
            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Removing...';

            await this.removeArticleFromList(currentListId, this.currentRemovingArticle.id);

            this.closeModal('removeModal');

            // Reload the reading list
            await this.loadReadingList(currentListId);

        } catch (error) {
            console.error('Error removing article:', error);
            uiUtils.showModal('errorModal', 'Failed to remove article from reading list');
        } finally {
            const confirmBtn = document.getElementById('confirmRemoveBtn');
            confirmBtn.disabled = false;
            confirmBtn.textContent = 'Remove Article';
        }
    }

    async removeArticleFromList(listId, articleId) {
        if (!listId) {
            throw new Error('Missing reading list id');
        }

        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${encodeURIComponent(listId)}/articles/${encodeURIComponent(articleId)}`, {
            method: 'DELETE'
        });

        if (!response) {
            return;
        }

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to remove article from reading list');
        }

        return response.json();
    }

    async moveArticle(articleId, direction) {
        const currentListId = this.resolveListId(this.currentList);
        if (!currentListId) {
            uiUtils.showModal('errorModal', 'Reading list information is missing. Please reopen the list.');
            return;
        }

        try {
            const currentIndex = this.articles.findIndex(article => article.id === articleId);
            if (currentIndex === -1) return;

            let newIndex;
            if (direction === 'up') {
                if (currentIndex === 0) return; // Already at top
                newIndex = currentIndex - 1;
            } else {
                if (currentIndex === this.articles.length - 1) return; // Already at bottom
                newIndex = currentIndex + 1;
            }

            // Create new order array
            const newArticles = [...this.articles];
            const [movedArticle] = newArticles.splice(currentIndex, 1);
            newArticles.splice(newIndex, 0, movedArticle);

            // Update local state
            this.articles = newArticles;

            // Update UI immediately for better UX
            this.renderArticles();

            // Send to backend
            const orderedArticleIds = this.articles.map(article => article.id);
            await this.reorderArticlesInList(currentListId, orderedArticleIds);

        } catch (error) {
            console.error('Error moving article:', error);
            // Reload to reset state on error
            await this.loadReadingList(currentListId);
            uiUtils.showModal('errorModal', 'Failed to reorder article. Please try again.');
        }
    }

    async reorderArticlesInList(listId, orderedArticleIds) {
        if (!listId) {
            throw new Error('Missing reading list id');
        }

        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${encodeURIComponent(listId)}/articles/reorder`, {
            method: 'PUT',
            body: JSON.stringify({ orderedArticleIds })
        });

        if (!response) {
            return;
        }

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to reorder articles');
        }

        return response.json();
    }

    async analyzeArticleBias(article) {
        this.currentAnalysisArticle = article;

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
                const publishDate = article.publishDate ? new Date(article.publishDate).toLocaleDateString() : '';
                originalContainer.innerHTML = `
                    <h4>${this.escapeHtml(article.title)}</h4>
                    <div class="article-meta">
                        <span class="article-source">${this.escapeHtml(article.source || '')}</span>
                        <span class="article-date">${publishDate}</span>
                    </div>
                    <div class="article-full-content">${this.escapeHtml(this.stripHtml(article.content || article.description || ''))}</div>
                `;
            }

            // Call bias analysis API
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/news/${article.id}/bias`, {
                method: 'POST'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                throw new Error(`Bias analysis failed: ${response.status}`);
            }

            const biasData = await response.json();
            console.log('Bias analysis response:', biasData);

            // Display bias analysis
            if (aiContainer) {
                aiContainer.innerHTML = this.formatBiasAnalysis(biasData);
            }

            if (loadingEl) loadingEl.classList.add('hidden');
            if (contentEl) contentEl.classList.remove('hidden');

        } catch (error) {
            console.error('Bias analysis failed:', error);
            if (aiContainer) {
                aiContainer.innerHTML = `<p class="alert alert-error">${this.escapeHtml(error.message)}</p>`;
            }
            if (loadingEl) loadingEl.classList.add('hidden');
            if (contentEl) contentEl.classList.remove('hidden');
        }
    }

    formatBiasAnalysis(data) {
        // Simple formatting for bias analysis - can be enhanced later
        if (typeof data === 'string') {
            return `<p>${this.escapeHtml(data)}</p>`;
        }

        if (data && typeof data === 'object') {
            let html = '<div class="bias-analysis-result">';

            if (data.quick_summary) {
                html += '<h4>Quick Summary</h4>';
                html += `<p>${this.escapeHtml(JSON.stringify(data.quick_summary, null, 2))}</p>`;
            }

            if (data.detailed_analysis) {
                html += '<h4>Detailed Analysis</h4>';
                html += `<p>${this.escapeHtml(JSON.stringify(data.detailed_analysis, null, 2))}</p>`;
            }

            html += '</div>';
            return html;
        }

        return '<p>No bias analysis available.</p>';
    }

    exportArticle(article) {
        try {
            console.log('Exporting article:', article.id);

            // Show export modal with article data
            if (window.exportModal) {
                window.exportModal.show(article);
            } else {
                console.error('Export modal not available');
                uiUtils.showModal('errorModal', 'Export functionality not available.');
            }

        } catch (error) {
            console.error('Error exporting article:', error);
            uiUtils.showModal('errorModal', 'Failed to export article. Please try again.');
        }
    }

    closeBiasModal() {
        const modal = document.getElementById('bias-analysis-modal');
        if (modal) {
            modal.style.display = 'none';
            modal.classList.remove('active');
        }
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

    resolveListId(list) {
        if (!list) return null;
        const candidate = list.id ?? list.listId ?? list._id ?? list.readingListId ?? null;
        if (candidate === null || candidate === undefined) {
            return null;
        }
        const asString = String(candidate).trim();
        return asString.length > 0 ? asString : null;
    }

    showModal(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.style.display = 'flex';
        }
    }

    closeModal(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.style.display = 'none';
        }
    }

    closeAllModals() {
        this.closeModal('removeModal');
        this.closeModal('shareModal');
        uiUtils.closeModal('errorModal');
        this.closeBiasModal();
    }

    // Sharing functionality
    showShareModal() {
        if (!this.currentList) {
            uiUtils.showModal('errorModal', 'No reading list loaded');
            return;
        }

        // Set current privacy state
        const privateRadio = document.getElementById('privateRadio');
        const publicRadio = document.getElementById('publicRadio');
        const shareUrlSection = document.getElementById('shareUrlSection');

        // Remove existing event listeners to prevent duplicates
        if (privateRadio && publicRadio) {
            privateRadio.removeEventListener('change', this.handlePrivacyChange.bind(this));
            publicRadio.removeEventListener('change', this.handlePrivacyChange.bind(this));

            // Add fresh event listeners
            privateRadio.addEventListener('change', this.handlePrivacyChange.bind(this));
            publicRadio.addEventListener('change', this.handlePrivacyChange.bind(this));
        }

        // Set initial state based on current list
        console.log('Current list sharing state:', this.currentList.isPublic, this.currentList.shareToken);

        if (this.currentList.isPublic) {
            if (publicRadio) publicRadio.checked = true;
            if (privateRadio) privateRadio.checked = false;
            if (shareUrlSection) shareUrlSection.style.display = 'block';
            this.updateShareUrl();
        } else {
            if (privateRadio) privateRadio.checked = true;
            if (publicRadio) publicRadio.checked = false;
            if (shareUrlSection) shareUrlSection.style.display = 'none';
        }

        this.showModal('shareModal');
    }

    handlePrivacyChange() {
        console.log('Privacy change handler called');
        const publicRadio = document.getElementById('publicRadio');
        const shareUrlSection = document.getElementById('shareUrlSection');

        if (publicRadio && publicRadio.checked) {
            console.log('Switching to public mode');
            if (shareUrlSection) {
                shareUrlSection.style.display = 'block';
            }
            this.updateShareUrl();
        } else {
            console.log('Switching to private mode');
            if (shareUrlSection) {
                shareUrlSection.style.display = 'none';
            }
        }
    }

    async handleShareSave() {
        try {
            const publicRadio = document.getElementById('publicRadio');
            const saveBtn = document.getElementById('saveShareBtn');

            if (!publicRadio) {
                uiUtils.showModal('errorModal', 'Privacy option not found');
                return;
            }

            const currentListId = this.resolveListId(this.currentList);
            if (!currentListId) {
                uiUtils.showModal('errorModal', 'Reading list information is missing. Please reopen the list.');
                return;
            }

            saveBtn.disabled = true;
            saveBtn.textContent = 'Saving...';

            const isPublic = publicRadio.checked;
            console.log('Saving share settings, making list public:', isPublic);

            if (isPublic) {
                const shareData = await this.makeListPublic(currentListId);
                console.log('Share data received:', shareData);
            } else {
                const shareData = await this.makeListPrivate(currentListId);
                console.log('Unshare data received:', shareData);
            }

            // Reload the reading list details to get the updated sharing state
            await this.loadReadingListDetails(currentListId);

            // Update the share URL after reloading details
            this.updateShareUrl();

            this.closeModal('shareModal');
            this.showShareFeedback(isPublic ? 'List is now public' : 'List is now private');

        } catch (error) {
            console.error('Error saving share settings:', error);
            uiUtils.showModal('errorModal', 'Failed to update sharing settings: ' + error.message);
        } finally {
            const saveBtn = document.getElementById('saveShareBtn');
            if (saveBtn) {
                saveBtn.disabled = false;
                saveBtn.textContent = 'Save Changes';
            }
        }
    }

    async makeListPublic(listId) {
        if (!listId) {
            throw new Error('Missing reading list id');
        }

        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${encodeURIComponent(listId)}/share`, {
            method: 'PUT'
        });

        if (!response) {
            return;
        }

        if (!response.ok) {
            const errorText = await response.text();
            let errorMessage = 'Failed to make list public';
            try {
                const errorJson = JSON.parse(errorText);
                errorMessage = errorJson.error || errorMessage;
            } catch (e) {
                // Use default message if JSON parsing fails
            }
            throw new Error(errorMessage);
        }

        const shareData = await response.json();
        console.log('makeListPublic response:', shareData);
        return shareData;
    }

    async makeListPrivate(listId) {
        if (!listId) {
            throw new Error('Missing reading list id');
        }

        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${encodeURIComponent(listId)}/share`, {
            method: 'DELETE'
        });

        if (!response) {
            return;
        }

        if (!response.ok) {
            const errorText = await response.text();
            let errorMessage = 'Failed to make list private';
            try {
                const errorJson = JSON.parse(errorText);
                errorMessage = errorJson.error || errorMessage;
            } catch (e) {
                // Use default message if JSON parsing fails
            }
            throw new Error(errorMessage);
        }

        const shareData = await response.json();
        console.log('makeListPrivate response:', shareData);
        return shareData;
    }

    updateShareUrl() {
        const shareUrlInput = document.getElementById('shareUrlInput');
        if (shareUrlInput) {
            if (this.currentList && this.currentList.shareToken) {
                const baseUrl = window.location.origin;
                const shareUrl = `${baseUrl}/shared-reading-list.html?token=${this.currentList.shareToken}`;
                shareUrlInput.value = shareUrl;
            } else {
                // Generate a placeholder URL to show what it will look like
                const baseUrl = window.location.origin;
                shareUrlInput.value = `${baseUrl}/shared-reading-list.html?token=...`;
                shareUrlInput.style.opacity = '0.6';
            }
        }
    }

    async copyShareUrl() {
        const shareUrlInput = document.getElementById('shareUrlInput');
        const copyBtn = document.getElementById('copyUrlBtn');

        if (!shareUrlInput || !shareUrlInput.value || shareUrlInput.value.includes('...')) {
            uiUtils.showModal('errorModal', 'Please save changes first to generate a share URL');
            return;
        }

        try {
            await navigator.clipboard.writeText(shareUrlInput.value);

            // Visual feedback
            const originalText = copyBtn.innerHTML;
            copyBtn.innerHTML = '<i class="fas fa-check"></i> Copied!';
            copyBtn.classList.add('success');

            setTimeout(() => {
                copyBtn.innerHTML = originalText;
                copyBtn.classList.remove('success');
            }, 2000);

        } catch (error) {
            console.error('Failed to copy URL:', error);

            // Fallback: select text for manual copy
            shareUrlInput.select();
            shareUrlInput.setSelectionRange(0, 99999);

            this.showShareFeedback('URL selected - press Ctrl+C to copy');
        }
    }

    showShareFeedback(message) {
        const toast = document.createElement('div');
        toast.className = 'share-toast glass-card';
        toast.innerHTML = `
            <i class="fas fa-share"></i>
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

}

// Global functions for modal handling
function closeRemoveModal() {
    if (window.readingListViewManager) {
        window.readingListViewManager.closeModal('removeModal');
    }
}

function closeShareModal() {
    if (window.readingListViewManager) {
        window.readingListViewManager.closeModal('shareModal');
    }
}

function closeBiasModal() {
    if (window.readingListViewManager) {
        window.readingListViewManager.closeBiasModal();
    }
}

// Initialize when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.readingListViewManager = new ReadingListViewManager();
});