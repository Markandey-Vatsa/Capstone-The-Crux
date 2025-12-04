/**
 * Export Modal Component
 * Handles the export modal interface for selecting format and downloading files
 */

class ExportModal {
    constructor() {
        this.modal = null;
        this.selectedFormat = null;
        this.articleData = null;
        this.isProcessing = false;
        
        this.createModal();
        this.bindEvents();
    }

    /**
     * Create the modal HTML structure
     */
    createModal() {
        const modalHTML = `
            <div class="modal" id="export-modal">
                <div class="modal-content export-modal-container">
                    <div class="modal-header">
                        <h2><i class="fas fa-download"></i> Export Article</h2>
                        <button class="modal-close" id="export-modal-close">&times;</button>
                    </div>
                    <div class="modal-body">
                        <div class="export-content">
                            <div class="article-preview">
                                <h3 id="export-article-title">Article Title</h3>
                                <p id="export-article-meta">Source • Date</p>
                            </div>
                            
                            <div class="format-selection">
                                <h4 id="format-selection-heading">Select Export Format:</h4>
                                <div class="format-options" role="radiogroup" aria-labelledby="format-selection-heading">
                                    <label class="format-option">
                                        <input type="radio" name="export-format" value="pdf" id="format-pdf" aria-describedby="pdf-description">
                                        <span class="format-label">
                                            <i class="fas fa-file-pdf" aria-hidden="true"></i>
                                            <strong>PDF</strong>
                                            <small id="pdf-description">Formatted document with styling</small>
                                        </span>
                                    </label>
                                    
                                    <label class="format-option">
                                        <input type="radio" name="export-format" value="txt" id="format-txt" aria-describedby="txt-description">
                                        <span class="format-label">
                                            <i class="fas fa-file-alt" aria-hidden="true"></i>
                                            <strong>TXT</strong>
                                            <small id="txt-description">Plain text format</small>
                                        </span>
                                    </label>
                                </div>
                            </div>
                            
                            <div class="export-actions">
                                <button class="btn btn-secondary" id="export-cancel" aria-label="Cancel export">Cancel</button>
                                <button class="btn btn-primary" id="export-download" disabled aria-label="Download selected format">
                                    <i class="fas fa-download" aria-hidden="true"></i>
                                    <span class="btn-text">Download</span>
                                </button>
                            </div>
                            
                            <div class="export-loading hidden" id="export-loading">
                                <div class="spinner"></div>
                                <p>Generating file...</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;

        // Add modal to body
        document.body.insertAdjacentHTML('beforeend', modalHTML);
        this.modal = document.getElementById('export-modal');
    }

    /**
     * Bind event listeners
     */
    bindEvents() {
        // Close modal events
        const closeBtn = document.getElementById('export-modal-close');
        const cancelBtn = document.getElementById('export-cancel');
        
        closeBtn.addEventListener('click', () => this.hide());
        cancelBtn.addEventListener('click', () => this.hide());
        
        // Click outside to close
        this.modal.addEventListener('click', (e) => {
            if (e.target === this.modal) {
                this.hide();
            }
        });

        // Format selection
        const formatOptions = document.querySelectorAll('input[name="export-format"]');
        formatOptions.forEach(option => {
            option.addEventListener('change', (e) => {
                this.handleFormatSelection(e.target.value);
            });
        });

        // Download button
        const downloadBtn = document.getElementById('export-download');
        downloadBtn.addEventListener('click', () => this.handleDownload());

        // Keyboard events
        document.addEventListener('keydown', (e) => {
            if (this.modal.classList.contains('active')) {
                if (e.key === 'Escape') {
                    this.hide();
                } else if (e.key === 'Tab') {
                    this.handleTabNavigation(e);
                }
            }
        });
    }

    /**
     * Show the export modal with article data
     * @param {Object} articleData - Article data to export
     */
    show(articleData) {
        if (!articleData) {
            console.error('No article data provided for export');
            return;
        }

        this.articleData = articleData;
        this.selectedFormat = null;
        this.isProcessing = false;

        // Update modal content
        this.updateModalContent();
        
        // Reset form state
        this.resetFormState();
        
        // Show modal
        this.modal.classList.add('active');
        document.body.style.overflow = 'hidden';

        // Focus management
        const firstRadio = document.getElementById('format-pdf');
        if (firstRadio) {
            firstRadio.focus();
        }
    }

    /**
     * Hide the export modal
     */
    hide() {
        if (this.isProcessing) {
            console.log('Cannot close modal while processing export');
            return; // Prevent closing during processing
        }

        this.modal.classList.remove('active');
        document.body.style.overflow = '';
        
        // Reset state
        this.articleData = null;
        this.selectedFormat = null;
        
        // Reset form state when closing
        this.resetFormState();
    }

    /**
     * Update modal content with article data
     */
    updateModalContent() {
        const titleEl = document.getElementById('export-article-title');
        const metaEl = document.getElementById('export-article-meta');

        titleEl.textContent = this.articleData.title || 'Untitled Article';
        
        const source = this.articleData.source || 'Unknown Source';
        const date = this.articleData.publishedAt 
            ? new Date(this.articleData.publishedAt).toLocaleDateString()
            : 'Unknown Date';
        
        metaEl.textContent = `${source} • ${date}`;
    }

    /**
     * Reset form state
     */
    resetFormState() {
        // Clear format selection
        const formatOptions = document.querySelectorAll('input[name="export-format"]');
        formatOptions.forEach(option => {
            option.checked = false;
        });

        // Disable download button
        const downloadBtn = document.getElementById('export-download');
        downloadBtn.disabled = true;

        // Hide loading
        const loadingEl = document.getElementById('export-loading');
        loadingEl.classList.add('hidden');
    }

    /**
     * Handle format selection
     * @param {string} format - Selected format (pdf or txt)
     */
    handleFormatSelection(format) {
        this.selectedFormat = format;
        
        // Enable download button
        const downloadBtn = document.getElementById('export-download');
        downloadBtn.disabled = false;

        // Update button text based on format
        downloadBtn.innerHTML = `<i class="fas fa-download" aria-hidden="true"></i> <span class="btn-text">Download ${format.toUpperCase()}</span>`;

        // Visual feedback for selection
        const formatOptions = document.querySelectorAll('.format-option');
        formatOptions.forEach(option => {
            option.classList.remove('selected');
        });

        const selectedOption = document.querySelector(`input[value="${format}"]`).closest('.format-option');
        selectedOption.classList.add('selected');
    }

    /**
     * Handle download button click
     */
    async handleDownload() {
        if (!this.selectedFormat || !this.articleData || this.isProcessing) {
            return;
        }

        this.isProcessing = true;
        
        try {
            // Show loading state
            this.showLoading();

            let blob;
            let filename;

            // Generate file based on selected format
            if (this.selectedFormat === 'pdf') {
                blob = await PDFGenerator.generatePDF(this.articleData);
                filename = 'news.pdf';
            } else if (this.selectedFormat === 'txt') {
                blob = TXTGenerator.generateTXT(this.articleData);
                filename = 'news.txt';
            } else {
                throw new Error('Invalid format selected');
            }

            // Validate blob
            if (!blob || blob.size === 0) {
                throw new Error('Generated file is empty or invalid');
            }

            // Download file
            this.downloadFile(blob, filename);

            // Show success feedback
            this.showSuccess();

            // Reset button state after short delay (don't close modal)
            setTimeout(() => {
                this.resetButtonState();
            }, 2000);

        } catch (error) {
            console.error('Export failed:', error);
            let errorMessage = error.message;
            
            // Provide more user-friendly error messages
            if (errorMessage.includes('jsPDF')) {
                errorMessage = 'PDF generation failed. Please refresh the page and try again, or try downloading as TXT format.';
            }
            
            this.showError(errorMessage);
        } finally {
            this.isProcessing = false;
        }
    }

    /**
     * Show loading state
     */
    showLoading() {
        const loadingEl = document.getElementById('export-loading');
        const downloadBtn = document.getElementById('export-download');
        
        loadingEl.classList.remove('hidden');
        downloadBtn.disabled = true;
        downloadBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Generating...';
    }

    /**
     * Show success state
     */
    showSuccess() {
        const loadingEl = document.getElementById('export-loading');
        const downloadBtn = document.getElementById('export-download');
        
        loadingEl.classList.add('hidden');
        downloadBtn.innerHTML = '<i class="fas fa-check"></i> Downloaded!';
        downloadBtn.classList.add('btn-success');
        downloadBtn.disabled = true;
    }

    /**
     * Reset button state for multiple downloads
     */
    resetButtonState() {
        const downloadBtn = document.getElementById('export-download');
        
        if (downloadBtn) {
            downloadBtn.classList.remove('btn-success', 'btn-error');
            downloadBtn.disabled = false;
            
            // Update button text based on current selection
            const btnText = downloadBtn.querySelector('.btn-text');
            if (this.selectedFormat) {
                btnText.textContent = `Download ${this.selectedFormat.toUpperCase()}`;
                downloadBtn.innerHTML = `<i class="fas fa-download" aria-hidden="true"></i> <span class="btn-text">Download ${this.selectedFormat.toUpperCase()}</span>`;
            } else {
                btnText.textContent = 'Download';
                downloadBtn.innerHTML = `<i class="fas fa-download" aria-hidden="true"></i> <span class="btn-text">Download</span>`;
            }
        }
    }

    /**
     * Show error state
     * @param {string} message - Error message
     */
    showError(message) {
        const loadingEl = document.getElementById('export-loading');
        const downloadBtn = document.getElementById('export-download');
        
        loadingEl.classList.add('hidden');
        downloadBtn.disabled = false;
        downloadBtn.innerHTML = '<i class="fas fa-exclamation-triangle"></i> Error - Try Again';
        downloadBtn.classList.add('btn-error');

        // Show error message
        alert(`Export failed: ${message}`);

        // Reset button after delay
        setTimeout(() => {
            this.resetButtonState();
        }, 3000);
    }

    /**
     * Handle tab navigation for accessibility
     * @param {KeyboardEvent} e - Keyboard event
     */
    handleTabNavigation(e) {
        const focusableElements = this.modal.querySelectorAll(
            'button:not([disabled]), input[type="radio"]:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        
        const firstElement = focusableElements[0];
        const lastElement = focusableElements[focusableElements.length - 1];

        if (e.shiftKey) {
            // Shift + Tab (backward)
            if (document.activeElement === firstElement) {
                e.preventDefault();
                lastElement.focus();
            }
        } else {
            // Tab (forward)
            if (document.activeElement === lastElement) {
                e.preventDefault();
                firstElement.focus();
            }
        }
    }

    /**
     * Download file using blob
     * @param {Blob} blob - File blob
     * @param {string} filename - File name
     */
    downloadFile(blob, filename) {
        try {
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            
            link.href = url;
            link.download = filename;
            link.style.display = 'none';
            
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            
            // Clean up URL object
            setTimeout(() => {
                URL.revokeObjectURL(url);
            }, 1000);

        } catch (error) {
            console.error('Download failed:', error);
            throw new Error('Failed to download file');
        }
    }
}

// Create global instance
window.exportModal = new ExportModal();