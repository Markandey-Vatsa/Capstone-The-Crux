// Profile page functionality
class ProfileManager {
    constructor() {
        if (!window.API_BASE_URL) { window.API_BASE_URL = 'http://localhost:8080/api'; }
        this.baseUrl = window.API_BASE_URL;
        this.init();
    }

    init() {
        if (!authUtils.requireAuth()) {
            return;
        }

    // First hide loading overlay to prevent blur issues
    uiUtils.hideLoading();

        this.loadUserProfile();
        this.setupEventListeners();
        this.initializeVoiceSettings();
    }

    // Initialize TTS voice dropdown
    initializeVoiceSettings() {
        const voiceSelect = document.getElementById('voice-select');
        if (!voiceSelect) return;

        const populateVoices = () => {
            const voices = speechSynthesis.getVoices().filter(v => v.lang && v.lang.toLowerCase().startsWith('en'));
            const stored = localStorage.getItem('user_preferred_voice');
            voiceSelect.innerHTML = '<option value="">Default (Browser)</option>' +
                voices.map(v => `<option value="${v.name}">${v.name} (${v.lang})</option>`).join('');
            if (stored) {
                voiceSelect.value = stored;
                const label = document.getElementById('voice-selected-label');
                if (label) label.textContent = stored;
            }
        };

        // Populate now if already available
        if (speechSynthesis.getVoices().length) {
            populateVoices();
        }
        // Repopulate when voices change (async load in some browsers)
        speechSynthesis.addEventListener('voiceschanged', populateVoices);

        // Persist selection
        voiceSelect.addEventListener('change', () => {
            const val = voiceSelect.value;
            if (val) {
                localStorage.setItem('user_preferred_voice', val);
                const label = document.getElementById('voice-selected-label');
                if (label) label.textContent = val;
            } else {
                localStorage.removeItem('user_preferred_voice');
                const label = document.getElementById('voice-selected-label');
                if (label) label.textContent = 'Not set';
            }
        });
    }

    setupEventListeners() {
        // Logout button
        const logoutBtn = document.getElementById('logoutBtn');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', () => authUtils.logout());
        }
    }

    async loadUserProfile() {
        try {
            uiUtils.showLoading();

            const response = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/user/profile`, {
                method: 'GET'
            });

            if (!response) {
                return;
            }

            if (!response.ok) {
                throw new Error(`Failed to load profile: ${response.status}`);
            }

            const profile = await response.json();
            this.displayProfile(profile);

        } catch (error) {
            console.error('Error loading profile:', error);
            uiUtils.showModal('errorModal', 'Failed to load profile information. Please try again.');
        } finally {
            uiUtils.hideLoading();
        }
    }

    displayProfile(profile) {
        // Update profile header
        document.getElementById('profileUsername').textContent = profile.username || 'Unknown User';
        document.getElementById('profileEmail').textContent = profile.email || 'No email provided';
        document.getElementById('bookmarkCount').textContent = profile.bookmarkedCount || 0;

        // Update settings section
        document.getElementById('settingsUsername').textContent = profile.username || 'Unknown User';
        document.getElementById('settingsEmail').textContent = profile.email || 'No email provided';

        // For now, we don't have creation date, so show a placeholder
        document.getElementById('memberSince').textContent = 'Recently joined';

        // Initialize preferences UI once profile loaded
        this.initPreferenceButtons(profile);
    }

    initPreferenceButtons(profile) {
        // Profession list synced with backend ProfessionCategoryMapper (keep labels identical)
        const professionGroups = [
            { group: 'Finance & Business', items: ['Stock Trader', 'Investor', 'Business Owner', 'Entrepreneur'] },
            { group: 'Technology & Engineering', items: ['IT Professional', 'Software Engineer', 'Engineer', 'Data Scientist'] },
            { group: 'Science & Health', items: ['Healthcare Professional', 'Doctor', 'Researcher', 'Environmental Scientist'] },
            { group: 'Public Sector & Law', items: ['Government Employee', 'Policy Analyst', 'Lawyer', 'Journalist'] },
            { group: 'Education & Learning', items: ['Educator / Student', 'Student'] },
            { group: 'Sports & Performance', items: ['Athlete', 'Coach'] },
            { group: 'Industry & Specialist', items: ['Farmer', 'Automotive Professional'] }
        ];

        // Grouped taxonomy categories (must mirror backend TaxonomyUtil.CATEGORIES ordering & names)
        const groupedInterests = [
            { group: 'Business & Finance', items: ['Stock Market', 'Economy', 'Corporate News', 'Startups & Venture Capital', 'Real Estate', 'Cryptocurrency', 'Personal Finance', 'Agriculture'] },
            { group: 'Technology', items: ['Artificial Intelligence', 'Space & Astronomy', 'Gadgets & Devices', 'Cybersecurity', 'Biotechnology', 'Clean Energy', 'Gaming'] },
            { group: 'Politics & Governance', items: ['Indian Politics', 'International Politics', 'Elections', 'Law & Justice', 'Government Policy', 'Human Rights'] },
            { group: 'Science & Health', items: ['Medical Research', 'Public Health', 'Climate & Environment', 'Wildlife & Nature', 'Wellness'] },
            { group: 'Lifestyle & Culture', items: ['Movies & TV', 'Music', 'Books & Literature', 'Automobiles', 'Travel', 'Food & Dining', 'Fashion & Style'] },
            { group: 'Sports', items: ['Cricket', 'Football', 'Tennis', 'Motorsport', 'Olympics'] },
            { group: 'Digital & Media', items: ['Internet & Social Media', 'Opinion'] },
            { group: 'General News', items: ['Education', 'Crime', 'Disasters', 'Weather', 'Careers & Workplace'] }
        ];

        const legacyMap = {}; // Legacy normalization (kept placeholder)
        const profContainer = document.getElementById('profession-buttons');
        const intContainer = document.getElementById('interest-buttons');
        if (!profContainer || !intContainer) return;
        profContainer.innerHTML = professionGroups.map(g => {
            const buttons = g.items.map(p => `<button type="button" class="pref-btn" data-profession="${p}">${p}</button>`).join('');
            return `<div class="profession-group"><h5 class="interest-group-title">${g.group}</h5><div class="interest-group-buttons">${buttons}</div></div>`;
        }).join('');

        // No search needed with dropdowns

        // Build grouped interest buttons with collapsible headers
        const groupIcons = {
            'Business & Finance': 'fas fa-chart-line',
            'Technology': 'fas fa-microchip',
            'Politics & Governance': 'fas fa-landmark',
            'Science & Health': 'fas fa-flask',
            'Lifestyle & Culture': 'fas fa-palette',
            'Sports': 'fas fa-trophy',
            'Digital & Media': 'fas fa-wifi',
            'General News': 'fas fa-newspaper'
        };

        const groupsHtml = groupedInterests.map((group, index) => {
            const checkboxes = group.items.map(label => 
                `<div class="category-checkbox-item" data-value="${label}">
                    <div class="category-checkbox">
                        <i class="fas fa-check"></i>
                    </div>
                    <div class="category-label">${label}</div>
                </div>`
            ).join('');
            const icon = groupIcons[group.group] || 'fas fa-folder';
            const groupId = group.group.replace(/[^a-zA-Z0-9]/g, '-').toLowerCase();
            
            return `
                <div class="interest-accordion-item" data-group="${group.group}" id="accordion-${groupId}">
                    <div class="accordion-header" onclick="toggleAccordion('${groupId}')">
                        <div class="accordion-title">
                            <i class="${icon} accordion-icon"></i>
                            <h3>${group.group}</h3>
                        </div>
                        <div class="accordion-toggle">
                            <span>${group.items.length} categories</span>
                            <i class="fas fa-chevron-down accordion-arrow"></i>
                        </div>
                    </div>
                    <div class="accordion-content">
                        <div class="accordion-body">
                            <div class="select-all-section">
                                <div class="select-all-checkbox" onclick="toggleSelectAll('${groupId}')">
                                    <i class="fas fa-check"></i>
                                </div>
                                <div class="select-all-label" onclick="toggleSelectAll('${groupId}')">Select All</div>
                            </div>
                            <div class="category-checkboxes">
                                ${checkboxes}
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }).join('');

        // Add save button section
        const saveButtonHtml = `
            <div class="save-preferences-section">
                <span id="prefsStatus" class="preferences-status"></span>
                <button id="savePreferencesBtn" class="btn">
                    <i class="fas fa-save"></i>
                    Save Preferences
                </button>
            </div>
        `;
        
        intContainer.innerHTML = groupsHtml + saveButtonHtml;

        // Initialize accordion functionality
        setTimeout(() => {
            initializeAccordion();
        }, 50);

        const savedProfession = profile.profession;
        const savedInterestsRaw = profile.interests || [];
        // Normalize any legacy values to canonical set
        const savedInterests = new Set(savedInterestsRaw.map(val => legacyMap[val] || val));
        profContainer.querySelectorAll('button').forEach(btn => {
            if (btn.dataset.profession === savedProfession) btn.classList.add('active');
            btn.addEventListener('click', () => {
                profContainer.querySelectorAll('button').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
            });
        });
        // Set saved interests in accordion
        setTimeout(() => {
            intContainer.querySelectorAll('.category-checkbox-item').forEach(item => {
                if (savedInterests.has(item.dataset.value)) {
                    item.classList.add('selected');
                }
            });
            
            // Update select all states
            intContainer.querySelectorAll('.interest-accordion-item').forEach(accordion => {
                updateSelectAllState(accordion);
            });
            
            updateSelectionSummary();
        }, 100);

        // Initial summary update
        setTimeout(() => updateSelectionSummary(), 100);
        const saveBtn = document.getElementById('savePreferencesBtn');
        if (saveBtn && !saveBtn._bound) {
            saveBtn.addEventListener('click', () => this.savePreferences());
            saveBtn._bound = true;
        }
    }

    async savePreferences() {
        const profContainer = document.getElementById('profession-buttons');
        const intContainer = document.getElementById('interest-buttons');
        const statusEl = document.getElementById('prefsStatus');
        const profession = profContainer ? (profContainer.querySelector('button.active')?.dataset.profession || null) : null;
        const interests = intContainer ? [...intContainer.querySelectorAll('.category-checkbox-item.selected')].map(item => item.dataset.value) : [];
        try {
            if (statusEl) statusEl.textContent = 'Saving...';
            const res = await authUtils.makeAuthenticatedRequest(`${this.baseUrl}/user/preferences`, {
                method: 'PUT',
                body: JSON.stringify({ profession, interests })
            });
            if (!res) {
                return;
            }
            if (!res.ok) throw new Error('Save failed');
            if (statusEl) { statusEl.textContent = 'Saved'; statusEl.style.color = '#4caf50'; }
            this.showPrefsToast('Preferences saved', true);
        } catch (e) {
            console.error('Preference save failed', e);
            if (statusEl) { statusEl.textContent = 'Error saving'; statusEl.style.color = '#f44336'; }
            this.showPrefsToast('Failed to save preferences', false);
        }
    }

    showPrefsToast(message, success = true) {
        const existing = document.querySelector('.prefs-toast');
        if (existing) existing.remove();
        const toast = document.createElement('div');
        toast.className = `prefs-toast ${success ? 'success' : 'error'}`;
        toast.innerHTML = `
            <span class="icon">${success ? '✔' : '✖'}</span>
            <span>${message}</span>
        `;
        document.body.appendChild(toast);
        requestAnimationFrame(() => toast.classList.add('show'));
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 400);
        }, 3000);
    }

}

// Global function to close error modal
// Initialize profile manager when DOM is loaded
let profileManager;
document.addEventListener('DOMContentLoaded', () => {
    profileManager = new ProfileManager();
});

// Handle browser back/forward navigation
window.addEventListener('popstate', () => {
    // Reload profile data if user navigates back to this page
    if (window.profileManager) {
        window.profileManager.loadUserProfile();
    }
});

// Initialize accordion functionality
function initializeAccordion() {
    // Add click handlers to category checkboxes
    document.querySelectorAll('.category-checkbox-item').forEach(item => {
        item.addEventListener('click', () => {
            item.classList.toggle('selected');
            updateSelectAllState(item.closest('.interest-accordion-item'));
            updateSelectionSummary();
        });
    });
}

// Toggle accordion panel
function toggleAccordion(groupId) {
    const accordion = document.getElementById(`accordion-${groupId}`);
    accordion.classList.toggle('expanded');
}

// Toggle select all for a group
function toggleSelectAll(groupId) {
    const accordion = document.getElementById(`accordion-${groupId}`);
    const selectAllCheckbox = accordion.querySelector('.select-all-checkbox');
    const categoryItems = accordion.querySelectorAll('.category-checkbox-item');
    
    const isChecked = selectAllCheckbox.classList.contains('checked');
    
    if (isChecked) {
        // Unselect all
        selectAllCheckbox.classList.remove('checked');
        categoryItems.forEach(item => item.classList.remove('selected'));
    } else {
        // Select all
        selectAllCheckbox.classList.add('checked');
        categoryItems.forEach(item => item.classList.add('selected'));
    }
    
    updateSelectionSummary();
}

// Update select all state based on individual selections
function updateSelectAllState(accordion) {
    const selectAllCheckbox = accordion.querySelector('.select-all-checkbox');
    const categoryItems = accordion.querySelectorAll('.category-checkbox-item');
    const selectedItems = accordion.querySelectorAll('.category-checkbox-item.selected');
    
    if (selectedItems.length === categoryItems.length) {
        selectAllCheckbox.classList.add('checked');
    } else {
        selectAllCheckbox.classList.remove('checked');
    }
}

// Save button is now positioned under the grid, no sticky functionality needed

// Search functionality for categories
function initializeCategorySearch() {
    const searchInput = document.getElementById('category-search-input');
    if (!searchInput) return;

    searchInput.addEventListener('input', (e) => {
        const searchTerm = e.target.value.toLowerCase().trim();
        const groups = document.querySelectorAll('.interest-group');

        if (searchTerm === '') {
            // Show all groups and restore original collapsed state
            groups.forEach((group, index) => {
                group.style.display = 'block';
                const buttons = group.querySelectorAll('.pref-btn');
                buttons.forEach(btn => btn.style.display = 'block');

                // Restore default collapsed state (first 2 open)
                if (index > 1) {
                    group.classList.add('collapsed');
                } else {
                    group.classList.remove('collapsed');
                }
            });
            return;
        }

        groups.forEach(group => {
            const buttons = group.querySelectorAll('.pref-btn');
            let hasVisibleButtons = false;

            buttons.forEach(btn => {
                const buttonText = btn.textContent.toLowerCase();
                if (buttonText.includes(searchTerm)) {
                    btn.style.display = 'block';
                    hasVisibleButtons = true;
                } else {
                    btn.style.display = 'none';
                }
            });

            if (hasVisibleButtons) {
                group.style.display = 'block';
                group.classList.remove('collapsed'); // Expand groups with matches
            } else {
                group.style.display = 'none';
            }
        });
    });
}

// Add control buttons for expand/collapse all
function addGroupControls() {
    const intContainer = document.getElementById('interest-buttons');
    if (!intContainer) return;

    const controlsHtml = `
        <div class="group-controls" style="margin-bottom: 15px; display: flex; gap: 10px; justify-content: flex-end;">
            <button type="button" onclick="expandAllGroups()" class="btn-control">
                <i class="fas fa-expand-alt"></i> Expand All
            </button>
            <button type="button" onclick="collapseAllGroups()" class="btn-control">
                <i class="fas fa-compress-alt"></i> Collapse All
            </button>
        </div>
    `;

    intContainer.insertAdjacentHTML('afterbegin', controlsHtml);
}

// Update selection summary
function updateSelectionSummary() {
    const summary = document.getElementById('selection-summary');
    const selectedItems = document.querySelectorAll('#interest-buttons .category-checkbox-item.selected');
    const count = selectedItems.length;

    if (count > 0) {
        summary.style.display = 'block';

        if (count === 1) {
            summary.innerHTML = `<i class="fas fa-check-circle"></i> <span class="count">${count}</span> category selected`;
        } else {
            summary.innerHTML = `<i class="fas fa-check-circle"></i> <span class="count">${count}</span> categories selected`;
        }
    } else {
        summary.style.display = 'none';
    }
}

// Tab switching functionality
function switchTab(tabName) {
    // Update tab buttons
    document.querySelectorAll('.profile-tab').forEach(tab => {
        tab.classList.remove('active');
    });
    event.target.classList.add('active');
    
    // Update tab content
    document.querySelectorAll('.tab-content').forEach(content => {
        content.classList.remove('active');
    });
    document.getElementById(`${tabName}-tab`).classList.add('active');
}