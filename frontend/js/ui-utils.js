(function() {
    // --- Modal Controls ---
    function showModal(modalId, message = '') {
        const modal = document.getElementById(modalId);
        if (!modal) return;
        if (message) {
            const messageEl = modal.querySelector('.error-message-text');
            if (messageEl) {
                messageEl.textContent = message;
            }
        }
        modal.style.display = 'flex';
    }

    function closeModal(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.style.display = 'none';
        }
    }

    // --- Loading Overlay Controls (for page-specific loading) ---
    function showLoading(overlayId = 'loadingOverlay') {
        const overlay = document.getElementById(overlayId);
        if (overlay) {
            overlay.style.display = 'flex';
        }
    }

    function hideLoading(overlayId = 'loadingOverlay') {
        const overlay = document.getElementById(overlayId);
        if (overlay) {
            overlay.style.display = 'none';
        }
    }

    // --- Global Spinner Controls (for background fetches) ---
    function setGlobalSpinner(show) {
        const overlay = document.getElementById('globalSpinner');
        if (!overlay) return;
        if (show) {
            overlay.classList.add('active');
        } else {
            overlay.classList.remove('active');
        }
    }

    // --- Global Toast/Alert ---
    function showToast(message, type = 'info') {
        const existing = document.querySelector('.global-toast');
        if (existing) existing.remove();

        const toast = document.createElement('div');
        toast.className = `global-toast glass-card ${type}`;

        let icon = 'fas fa-info-circle';
        if (type === 'success') icon = 'fas fa-check-circle';
        if (type === 'error') icon = 'fas fa-exclamation-triangle';

        toast.innerHTML = `<i class="${icon}"></i><span>${message}</span>`;
        document.body.appendChild(toast);

        setTimeout(() => toast.classList.add('show'), 100);
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 400);
        }, 3000);
    }

    function ensureErrorModal() {
        if (document.getElementById('errorModal')) return;

        const modal = document.createElement('div');
        modal.id = 'errorModal';
        modal.className = 'modal-overlay';
        modal.style.display = 'none';
        modal.innerHTML = `
            <div class="modal glass-card">
                <div class="modal-header">
                    <h3><i class="fas fa-exclamation-triangle"></i> Error</h3>
                    <button class="close-btn">
                        <i class="fas fa-times"></i>
                    </button>
                </div>
                <div class="modal-body">
                    <p id="errorMessage" class="error-message-text">An error occurred.</p>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-primary">OK</button>
                </div>
            </div>`;

        document.body.appendChild(modal);
    }

    // Export to global window object
    window.uiUtils = {
        showModal,
        closeModal,
        showLoading,
        hideLoading,
        setGlobalSpinner,
        showToast
    };

    // Auto-bind close buttons for any error modals
    document.addEventListener('DOMContentLoaded', () => {
        ensureErrorModal();
        const errorModal = document.getElementById('errorModal');
        if (errorModal) {
            const closeBtns = errorModal.querySelectorAll('.close-btn, .btn-primary');
            closeBtns.forEach(btn => {
                btn.onclick = () => closeModal('errorModal');
            });
        }
    });
})();
