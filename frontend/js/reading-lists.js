// Reading Lists page functionality
class ReadingListsManager {
    constructor() {
        if (!window.API_BASE_URL) { 
            window.API_BASE_URL = 'http://localhost:8080/api'; 
        }
        this.baseUrl = window.API_BASE_URL;
        this.readingLists = [];
        this.currentEditingList = null;
        this.currentDeletingList = null;
        this.init();
    }

    init() {
        if (!authUtils.requireAuth()) {
            return;
        }

    // First hide loading overlay to prevent blur issues
    uiUtils.hideLoading();
        
        this.loadReadingLists();
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
            refreshBtn.addEventListener('click', this.loadReadingLists.bind(this));
        }

        // Create list buttons
        const createListBtn = document.getElementById('createListBtn');
        const createFirstListBtn = document.getElementById('createFirstListBtn');
        if (createListBtn) {
            createListBtn.addEventListener('click', () => this.showCreateListModal());
        }
        if (createFirstListBtn) {
            createFirstListBtn.addEventListener('click', () => this.showCreateListModal());
        }

        // List form submission
        const listForm = document.getElementById('listForm');
        if (listForm) {
            listForm.addEventListener('submit', this.handleListFormSubmit.bind(this));
        }

        // Color picker
        const colorPicker = document.getElementById('colorPicker');
        if (colorPicker) {
            colorPicker.addEventListener('click', this.handleColorSelection.bind(this));
        }

        // Delete confirmation
        const confirmDeleteBtn = document.getElementById('confirmDeleteBtn');
        if (confirmDeleteBtn) {
            confirmDeleteBtn.addEventListener('click', this.confirmDeleteList.bind(this));
        }

        // Close modals on escape key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeAllModals();
            }
        });
    }

    async loadReadingLists() {
        try {
            uiUtils.showLoading();

            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists`, {
                method: 'GET'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                throw new Error(`Failed to load reading lists: ${response.status}`);
            }

            this.readingLists = await response.json();
            if (Array.isArray(this.readingLists)) {
                this.readingLists = this.readingLists.map(list => {
                    const normalizedId = this.resolveListId(list);
                    return normalizedId ? { ...list, id: normalizedId } : { ...list };
                });
            } else {
                this.readingLists = [];
            }
            console.log('Loaded reading lists:', this.readingLists);
            this.renderReadingLists();
            
        } catch (error) {
            console.error('Error loading reading lists:', error);
            uiUtils.showModal('errorModal', 'Failed to load reading lists. Please try again.');
        } finally {
            uiUtils.hideLoading();
        }
    }

    renderReadingLists() {
        const grid = document.getElementById('readingListsGrid');
        const emptyState = document.getElementById('emptyState');
        const listsStats = document.getElementById('listsStats');
        
        if (!grid || !emptyState || !listsStats) return;

        const count = this.readingLists.length;
        
        if (count === 0) {
            listsStats.textContent = 'No reading lists created';
            grid.style.display = 'none';
            grid.innerHTML = '';
            emptyState.style.display = 'block';
            return;
        }

        // Non-empty state
        listsStats.textContent = `${count} reading list${count !== 1 ? 's' : ''}`;
        emptyState.style.display = 'none';
        grid.style.display = 'grid';

        // Rebuild grid content
        grid.innerHTML = '';
        this.readingLists.forEach(list => {
            const listCard = this.createListCard(list);
            grid.appendChild(listCard);
        });

        // Setup drag and drop for reordering
        this.setupDragAndDrop();
    }

    createListCard(list) {
        const listId = this.resolveListId(list);
        const card = document.createElement('div');
        card.className = 'reading-list-card glass-card';
        card.dataset.listId = listId || '';
        card.draggable = true;

        const createdDate = new Date(list.createdAt).toLocaleDateString();
        const updatedDate = new Date(list.updatedAt).toLocaleDateString();

        card.innerHTML = `
            <div class="list-card-header" style="border-left: 4px solid ${list.colorTheme};">
                <div class="drag-handle">
                    <i class="fas fa-grip-vertical"></i>
                </div>
                <div class="list-info">
                    <h3 class="list-name">${this.escapeHtml(list.name)}</h3>
                    <div class="list-meta">
                        <span class="article-count">${list.articleCount} article${list.articleCount !== 1 ? 's' : ''}</span>
                        <span class="list-date">Created ${createdDate}</span>
                    </div>
                </div>
                <div class="list-actions">
                    <button class="btn btn-small btn-secondary edit-btn" data-list-id="${listId || ''}" title="Edit list">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-small btn-danger delete-btn" data-list-id="${listId || ''}" title="Delete list">
                        <i class="fas fa-trash"></i>
                    </button>
                </div>
            </div>
            <div class="list-card-body">
                <div class="color-indicator" style="background-color: ${list.colorTheme};"></div>
                <p class="list-description">
                    ${list.articleCount === 0 ? 'No articles yet' : `Last updated ${updatedDate}`}
                </p>
                <button class="btn btn-primary view-list-btn" data-list-id="${listId || ''}">
                    <i class="fas fa-eye"></i>
                    View Articles
                </button>
            </div>
        `;

        // Add event listeners
        const editBtn = card.querySelector('.edit-btn');
        const deleteBtn = card.querySelector('.delete-btn');
        const viewBtn = card.querySelector('.view-list-btn');

        editBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            this.showEditListModal(list);
        });

        deleteBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            this.showDeleteConfirmation(list);
        });

        if (!listId) {
            console.warn('Reading list is missing an identifier and cannot be opened:', list);
            viewBtn.disabled = true;
            viewBtn.classList.add('disabled');
            viewBtn.title = 'Unable to open this list (missing identifier)';
        }

        viewBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            this.viewReadingList(listId);
        });

        return card;
    }

    showCreateListModal() {
        this.currentEditingList = null;
        document.getElementById('modalTitle').textContent = 'Create New Reading List';
        document.getElementById('saveListBtn').textContent = 'Create List';
        document.getElementById('listName').value = '';
        document.getElementById('selectedColor').value = '#3b82f6';
        
        // Reset color picker
        const colorOptions = document.querySelectorAll('.color-option');
        colorOptions.forEach(option => option.classList.remove('active'));
        document.querySelector('.color-option[data-color="#3b82f6"]').classList.add('active');
        
        this.showModal('listModal');
    }

    showEditListModal(list) {
        this.currentEditingList = list;
        document.getElementById('modalTitle').textContent = 'Edit Reading List';
        document.getElementById('saveListBtn').textContent = 'Save Changes';
        document.getElementById('listName').value = list.name;
        document.getElementById('selectedColor').value = list.colorTheme;
        
        // Set color picker
        const colorOptions = document.querySelectorAll('.color-option');
        colorOptions.forEach(option => option.classList.remove('active'));
        document.querySelector(`.color-option[data-color="${list.colorTheme}"]`).classList.add('active');
        
        this.showModal('listModal');
    }

    handleColorSelection(e) {
        if (e.target.classList.contains('color-option')) {
            // Remove active class from all options
            document.querySelectorAll('.color-option').forEach(option => {
                option.classList.remove('active');
            });
            
            // Add active class to clicked option
            e.target.classList.add('active');
            
            // Update hidden input
            document.getElementById('selectedColor').value = e.target.dataset.color;
        }
    }

    async handleListFormSubmit(e) {
        e.preventDefault();
        
        const formData = new FormData(e.target);
        const name = formData.get('name').trim();
        const colorTheme = formData.get('colorTheme');
        
        if (!name) {
            this.showNameError('List name cannot be empty');
            return;
        }
        
        try {
            const saveBtn = document.getElementById('saveListBtn');
            saveBtn.disabled = true;
            saveBtn.textContent = this.currentEditingList ? 'Saving...' : 'Creating...';
            
            if (this.currentEditingList) {
                await this.updateReadingList(this.currentEditingList.id, name, colorTheme);
            } else {
                await this.createReadingList(name, colorTheme);
            }
            
            this.closeModal('listModal');
            await this.loadReadingLists();
            
        } catch (error) {
            console.error('Error saving reading list:', error);
            uiUtils.showModal('errorModal', error.message || 'Failed to save reading list');
        } finally {
            const saveBtn = document.getElementById('saveListBtn');
            saveBtn.disabled = false;
            saveBtn.textContent = this.currentEditingList ? 'Save Changes' : 'Create List';
        }
    }

    async createReadingList(name, colorTheme) {
        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists`, {
            method: 'POST',
            body: JSON.stringify({ name, colorTheme })
        });

        if (!response) {
            throw new Error('Failed to create reading list');
        }

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to create reading list');
        }

        return response.json();
    }

    async updateReadingList(listId, name, colorTheme) {
        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${listId}`, {
            method: 'PUT',
            body: JSON.stringify({ name, colorTheme })
        });

        if (!response) {
            throw new Error('Failed to update reading list');
        }

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to update reading list');
        }

        return response.json();
    }

    showDeleteConfirmation(list) {
        this.currentDeletingList = list;
        document.getElementById('deleteListName').textContent = list.name;
        this.showModal('deleteModal');
    }

    async confirmDeleteList() {
        if (!this.currentDeletingList) return;
        
        try {
            const confirmBtn = document.getElementById('confirmDeleteBtn');
            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Deleting...';
            
            await this.deleteReadingList(this.currentDeletingList.id);
            
            this.closeModal('deleteModal');
            await this.loadReadingLists();
            
        } catch (error) {
            console.error('Error deleting reading list:', error);
            uiUtils.showModal('errorModal', 'Failed to delete reading list');
        } finally {
            const confirmBtn = document.getElementById('confirmDeleteBtn');
            confirmBtn.disabled = false;
            confirmBtn.textContent = 'Delete List';
        }
    }

    async deleteReadingList(listId) {
        const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/${listId}`, {
            method: 'DELETE'
        });

        if (!response) {
            throw new Error('Failed to delete reading list');
        }

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to delete reading list');
        }

        return response.json();
    }

    viewReadingList(listId) {
        const safeListId = (listId || '').trim();
        if (!safeListId) {
            uiUtils.showModal('errorModal', 'Unable to open this reading list. Please refresh and try again.');
            return;
        }

        // Navigate to the reading list view page
        window.location.href = `reading-list-view.html?id=${encodeURIComponent(safeListId)}`;
    }

    setupDragAndDrop() {
        const grid = document.getElementById('readingListsGrid');
        if (!grid) return;

        let draggedElement = null;

        grid.addEventListener('dragstart', (e) => {
            if (e.target.classList.contains('reading-list-card')) {
                draggedElement = e.target;
                e.target.style.opacity = '0.5';
            }
        });

        grid.addEventListener('dragend', (e) => {
            if (e.target.classList.contains('reading-list-card')) {
                e.target.style.opacity = '';
                draggedElement = null;
            }
        });

        grid.addEventListener('dragover', (e) => {
            e.preventDefault();
        });

        grid.addEventListener('drop', (e) => {
            e.preventDefault();
            
            if (!draggedElement) return;
            
            const dropTarget = e.target.closest('.reading-list-card');
            if (dropTarget && dropTarget !== draggedElement) {
                const rect = dropTarget.getBoundingClientRect();
                const midpoint = rect.top + rect.height / 2;
                
                if (e.clientY < midpoint) {
                    grid.insertBefore(draggedElement, dropTarget);
                } else {
                    grid.insertBefore(draggedElement, dropTarget.nextSibling);
                }
                
                this.updateListOrder();
            }
        });
    }

    async updateListOrder() {
        const cards = document.querySelectorAll('.reading-list-card');
        const orderedListIds = Array.from(cards)
            .map(card => (card.dataset.listId || '').trim())
            .filter(id => id.length > 0);

        if (orderedListIds.length === 0) {
            console.warn('Skipping reading list reorder because no valid identifiers were found.');
            return;
        }
        
        try {
            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/reading-lists/reorder`, {
                method: 'PUT',
                body: JSON.stringify({ orderedListIds })
            });

            if (!response) {
                throw new Error('Failed to update list order');
            }

            if (!response.ok) {
                throw new Error('Failed to update list order');
            }
            
            // Reload to get updated order from server
            await this.loadReadingLists();
            
        } catch (error) {
            console.error('Error updating list order:', error);
            uiUtils.showModal('errorModal', 'Failed to update list order');
            // Reload to reset order
            await this.loadReadingLists();
        }
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

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
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
        this.closeModal('listModal');
        this.closeModal('deleteModal');
        uiUtils.closeModal('errorModal');
    }

    showNameError(message) {
        const errorEl = document.getElementById('nameError');
        if (errorEl) {
            errorEl.textContent = message;
            errorEl.style.display = 'block';
        }
    }

}

// Global functions for modal handling
function closeListModal() {
    if (window.readingListsManager) {
        window.readingListsManager.closeModal('listModal');
    }
}

function closeDeleteModal() {
    if (window.readingListsManager) {
        window.readingListsManager.closeModal('deleteModal');
    }
}

// Initialize when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.readingListsManager = new ReadingListsManager();
});