(() => {
  const DEFAULT_PAGE_SIZE = 50;

  const state = {
    pending: [],
    buckets: [],
    summary: {},
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      totalPages: 1,
      totalPending: 0,
    },
  };

  const selectedIndices = new Set();
  let filterValue = "";
  let isAssigning = false;
  let isSaving = false;

  const elements = {
    summaryCards: document.getElementById("summaryCards"),
    bucketSelect: document.getElementById("bucketSelect"),
    newBucketInput: document.getElementById("newBucketInput"),
    useNewBucketBtn: document.getElementById("useNewBucketBtn"),
    assignBtn: document.getElementById("assignBtn"),
    undoBtn: document.getElementById("undoBtn"),
    saveBtn: document.getElementById("saveBtn"),
    reloadBtn: document.getElementById("reloadBtn"),
    filterInput: document.getElementById("filterInput"),
    clearFilterBtn: document.getElementById("clearFilterBtn"),
    pendingTableBody: document.querySelector("#pendingTable tbody"),
    selectAll: document.getElementById("selectAll"),
    changesBadge: document.getElementById("changesBadge"),
    toastContainer: document.getElementById("toastContainer"),
    prevPageBtn: document.getElementById("prevPageBtn"),
    nextPageBtn: document.getElementById("nextPageBtn"),
    pageIndicator: document.getElementById("pageIndicator"),
    pageSummary: document.getElementById("pageSummary"),
  };

  function deriveMeta(metaData = {}, summaryData = {}, fallbackPage = 1, fallbackPageSize = DEFAULT_PAGE_SIZE) {
    const summaryPending = Number.isFinite(summaryData?.pending) ? Number(summaryData.pending) : 0;
    const totalPending = Number.isFinite(metaData?.totalPending)
      ? Number(metaData.totalPending)
      : summaryPending;

    const normalizedPageSize = Number(metaData?.pageSize) > 0
      ? Number(metaData.pageSize)
      : fallbackPageSize;

    let totalPages = Number(metaData?.totalPages);
    if (!Number.isFinite(totalPages) || totalPages <= 0) {
      totalPages = totalPending > 0 ? Math.ceil(totalPending / normalizedPageSize) : 1;
    }

    let page = Number(metaData?.page);
    if (!Number.isFinite(page) || page < 1) {
      page = fallbackPage;
    }

    if (totalPending <= 0) {
      return {
        page: 1,
        pageSize: normalizedPageSize,
        totalPages: 1,
        totalPending: 0,
      };
    }

    page = Math.min(Math.max(1, page), Math.max(totalPages, 1));

    return {
      page,
      pageSize: normalizedPageSize,
      totalPages: Math.max(totalPages, 1),
      totalPending,
    };
  }

  async function fetchState(options = {}) {
    const targetPage = Number.isInteger(options.page) ? options.page : state.meta.page || 1;
    const pageSize = Number.isInteger(options.pageSize) ? options.pageSize : state.meta.pageSize || DEFAULT_PAGE_SIZE;

    try {
      const params = new URLSearchParams({
        page: String(targetPage),
        page_size: String(pageSize),
      });
      const res = await fetch(`/api/state?${params.toString()}`, { cache: "no-store" });
      if (!res.ok) throw new Error(`Failed to load state (${res.status})`);
      const data = await res.json();

      const meta = deriveMeta(data.meta, data.summary, targetPage, pageSize);
      if (meta.page !== targetPage || meta.pageSize !== pageSize) {
        state.meta = meta;
        await fetchState({ page: meta.page, pageSize: meta.pageSize });
        return;
      }

      state.meta = meta;
      state.pending = data.pending || [];
      state.buckets = data.buckets || [];
      state.summary = data.summary || {};
      selectedIndices.clear();
      renderAll();
    } catch (err) {
      showAlert(err.message || "Failed to load state", "danger");
    }
  }

  function renderAll() {
    renderSummary();
    renderBuckets();
  const pageStats = renderPending();
  renderPagination(pageStats);
    updateChangesBadge();
  }

  function renderSummary() {
    const cards = [
      {
        title: "Pending Keywords",
        value: state.summary.pending ?? state.meta.totalPending ?? state.pending.length,
        variant: "primary",
      },
      {
        title: "Assigned This Session",
        value: state.summary.mapped ?? 0,
        variant: "info",
      },
      {
        title: "Newly Added Keywords",
        value: state.summary.newly_added ?? 0,
        variant: "success",
      },
      {
        title: "Buckets",
        value: state.summary.buckets ?? state.buckets.length,
        variant: "secondary",
      },
    ];

    elements.summaryCards.innerHTML = cards
      .map(
        (card) => `
        <div class="col">
          <div class="card h-100 border-${card.variant}">
            <div class="card-body d-flex flex-column justify-content-center text-center">
              <div class="text-muted small text-uppercase">${card.title}</div>
              <div class="fs-4 fw-bold">${card.value}</div>
            </div>
          </div>
        </div>`
      )
      .join("");
  }

  function renderBuckets() {
    const previous = elements.bucketSelect.value;
    const options = state.buckets
      .map(
        (bucket) => `
        <option value="${bucket.name}">
          ${bucket.name} (${bucket.customCount} custom / ${bucket.baseCount} base)
        </option>`
      )
      .join("");

    elements.bucketSelect.innerHTML = options;
    if (previous) elements.bucketSelect.value = previous;
  }

  function renderPending() {
    const filter = filterValue.trim().toLowerCase();
    const filtered = state.pending.filter((item) =>
      !filter || item.keyword.toLowerCase().includes(filter)
    );

    const rows = filtered
      .map((item) => {
        const actualIndex = Number(item.index);
        if (!Number.isInteger(actualIndex)) return null;
        const checked = selectedIndices.has(actualIndex) ? "checked" : "";
        const suggestions = (item.suggestions || [])
          .map((suggestion) => {
            const reasons = Array.isArray(suggestion.reasons)
              ? suggestion.reasons.join(", ")
              : suggestion.reasons || "";
            const score = typeof suggestion.score === "number"
              ? suggestion.score.toFixed(2)
              : suggestion.score ?? "";

            return `<span class="badge text-bg-light border suggestion-badge" title="${reasons}">
              ${suggestion.bucket}
              <span class="text-muted">${score}</span>
              <span class="text-muted">(${suggestion.source})</span>
            </span>`;
          })
          .join("") || "<span class='text-muted small'>No suggestions</span>";

        return `
          <tr data-index="${actualIndex}">
            <td><input type="checkbox" class="row-select" data-index="${actualIndex}" ${checked}></td>
            <td>${actualIndex + 1}</td>
            <td>${item.keyword}</td>
            <td>${suggestions}</td>
          </tr>`;
      })
      .filter(Boolean)
      .join("");

    elements.pendingTableBody.innerHTML = rows || `
      <tr>
        <td colspan="4" class="text-center text-muted py-4">No keywords on this page.</td>
      </tr>`;

    const hasRows = filtered.length > 0;
    elements.selectAll.disabled = !hasRows;
    elements.selectAll.checked = hasRows && filtered.every((item) => selectedIndices.has(Number(item.index)));

    const firstIndex = hasRows ? Number(filtered[0].index) : null;
    const lastIndex = hasRows ? Number(filtered[filtered.length - 1].index) : null;

    return {
      count: filtered.length,
      firstIndex: Number.isInteger(firstIndex) ? firstIndex : null,
      lastIndex: Number.isInteger(lastIndex) ? lastIndex : null,
    };
  }

  function renderPagination(pageStats = {}) {
    const { page, totalPages, pageSize, totalPending } = state.meta;
    const hasPending = totalPending > 0;
    const safeTotalPages = Math.max(totalPages, 1);
    const safePage = hasPending ? Math.min(Math.max(1, page), safeTotalPages) : 1;
    const count = Number.isInteger(pageStats.count) ? pageStats.count : 0;
    const firstIndex = Number.isInteger(pageStats.firstIndex) ? pageStats.firstIndex : null;
    const lastIndex = Number.isInteger(pageStats.lastIndex) ? pageStats.lastIndex : null;

    if (elements.pageIndicator) {
      elements.pageIndicator.textContent = hasPending
        ? `Page ${safePage} / ${safeTotalPages}`
        : "Page 0 / 0";
    }

    if (elements.prevPageBtn) {
      elements.prevPageBtn.disabled = !hasPending || safePage <= 1;
    }
    if (elements.nextPageBtn) {
      elements.nextPageBtn.disabled = !hasPending || safePage >= safeTotalPages;
    }

    if (elements.pageSummary) {
      if (!hasPending) {
        elements.pageSummary.textContent = "No pending keywords remaining.";
      } else if (count === 0) {
        elements.pageSummary.textContent = `No keywords match the current filter on page ${safePage}.`;
      } else {
        const start = firstIndex !== null ? firstIndex + 1 : (safePage - 1) * pageSize + 1;
        const end = lastIndex !== null ? lastIndex + 1 : start + count - 1;
        elements.pageSummary.textContent = `Showing ${start}-${end} of ${totalPending} keywords (page ${safePage} of ${safeTotalPages}).`;
      }
    }
  }

  function updateChangesBadge() {
    const hasChanges = Boolean(state.summary.hasChanges);
    elements.changesBadge.textContent = hasChanges ? "Unsaved changes" : "No unsaved changes";
    elements.changesBadge.className = `badge bg-${hasChanges ? "warning text-dark" : "secondary"}`;
  }

  function getSelectedBucket() {
    const newBucket = elements.newBucketInput.value.trim();
    if (newBucket) return newBucket;
    return elements.bucketSelect.value || "";
  }

  async function assignSelected() {
    if (isAssigning) return;

    const bucket = getSelectedBucket();
    if (!bucket) {
      showAlert("Choose or create a bucket first.", "warning");
      return;
    }

    if (selectedIndices.size === 0) {
      showAlert("Select at least one keyword to assign.", "warning");
      return;
    }

    const selectedEntries = getSelectedEntries();
    if (selectedEntries.length === 0) {
      showAlert("Selected keywords are no longer available. Refreshing list.", "danger");
      await fetchState();
      return;
    }

    const indices = selectedEntries
      .map((item) => Number(item.index))
      .filter((idx) => Number.isInteger(idx));

    const expectedKeywords = selectedEntries.map((item) => item.keyword);

    isAssigning = true;
    toggleBusy(elements.assignBtn, true);
    try {
      const res = await fetch("/api/assign", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ bucket, indices }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Assignment failed");

      const assignedKeywords = data.operation?.keywords || [];
      const alreadyMapped = data.operation?.already_mapped || [];
      const mismatch = findAssignmentMismatch(expectedKeywords, assignedKeywords);

      if (mismatch) {
        showAlert(mismatch, "warning");
      }

      const details = buildAssignmentMessage(assignedKeywords, alreadyMapped, bucket);
      showAlert(details, "success");

      filterValue = "";
      elements.filterInput.value = "";
      selectedIndices.clear();
      await fetchState();
    } catch (err) {
      showAlert(err.message || "Assignment failed", "danger");
    }
    finally {
      isAssigning = false;
      toggleBusy(elements.assignBtn, false);
    }
  }

  async function undoLast() {
    try {
      const res = await fetch("/api/undo", { method: "POST" });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Nothing to undo");
      showAlert(`Undid assignment from ${data.operation.bucket}.`, "info");
      await fetchState();
    } catch (err) {
      showAlert(err.message || "Undo failed", "danger");
    }
  }

  async function saveChanges() {
    if (isSaving) return;
    isSaving = true;
    toggleBusy(elements.saveBtn, true);
    try {
      const res = await fetch("/api/save", { method: "POST" });
      if (!res.ok) throw new Error("Save failed");
      showAlert("Changes saved and backups created.", "success");
      await fetchState();
    } catch (err) {
      showAlert(err.message || "Save failed", "danger");
    } finally {
      isSaving = false;
      toggleBusy(elements.saveBtn, false);
    }
  }

  async function reloadFromDisk() {
    try {
      const params = new URLSearchParams({
        page: String(1),
        page_size: String(state.meta.pageSize || DEFAULT_PAGE_SIZE),
      });
      const res = await fetch(`/api/reload?${params.toString()}`, { method: "POST" });
      if (!res.ok) throw new Error("Reload failed");
      const data = await res.json();
      state.meta = deriveMeta(data.meta, data.summary, 1, state.meta.pageSize || DEFAULT_PAGE_SIZE);
      state.pending = data.pending || [];
      state.buckets = data.buckets || [];
      state.summary = data.summary || {};
      selectedIndices.clear();
      filterValue = "";
      elements.filterInput.value = "";
      renderAll();
      showAlert("Reloaded taxonomy files from disk.", "info");
    } catch (err) {
      showAlert(err.message || "Reload failed", "danger");
    }
  }

  function toggleSelection(index, checked) {
    if (!Number.isInteger(index)) return;
    if (checked) {
      selectedIndices.add(index);
    } else {
      selectedIndices.delete(index);
    }
  const pageStats = renderPending();
  renderPagination(pageStats);
  }

  function handleSelectAll(checked) {
    const filter = filterValue.trim().toLowerCase();
    state.pending.forEach((item) => {
      if (!filter || item.keyword.toLowerCase().includes(filter)) {
        const idx = Number(item.index);
        if (!Number.isInteger(idx)) return;
        if (checked) selectedIndices.add(idx);
        else selectedIndices.delete(idx);
      }
    });
  const pageStats = renderPending();
  renderPagination(pageStats);
  }

  function getSelectedEntries() {
    const lookup = new Map(state.pending.map((item) => [Number(item.index), item]));
    return Array.from(selectedIndices)
      .map((idx) => lookup.get(idx))
      .filter(Boolean);
  }

  function bindEvents() {
    elements.pendingTableBody.addEventListener("change", (event) => {
      if (event.target.matches(".row-select")) {
        const index = Number(event.target.getAttribute("data-index"));
        toggleSelection(index, event.target.checked);
      }
    });

    elements.selectAll.addEventListener("change", (event) => {
      handleSelectAll(event.target.checked);
    });

    elements.filterInput.addEventListener("input", (event) => {
      filterValue = event.target.value;
  const pageStats = renderPending();
  renderPagination(pageStats);
    });

    elements.clearFilterBtn.addEventListener("click", () => {
      filterValue = "";
      elements.filterInput.value = "";
  const pageStats = renderPending();
  renderPagination(pageStats);
    });

    if (elements.prevPageBtn) {
      elements.prevPageBtn.addEventListener("click", () => {
        if (state.meta.page > 1) {
          fetchState({ page: state.meta.page - 1 });
        }
      });
    }

    if (elements.nextPageBtn) {
      elements.nextPageBtn.addEventListener("click", () => {
        if (state.meta.page < state.meta.totalPages) {
          fetchState({ page: state.meta.page + 1 });
        }
      });
    }

    elements.useNewBucketBtn.addEventListener("click", () => {
      const value = elements.newBucketInput.value.trim();
      if (!value) {
        showAlert("Enter a name for the new bucket first.", "warning");
        return;
      }
      if (!state.buckets.find((bucket) => bucket.name === value)) {
        state.buckets.unshift({ name: value, customCount: 0, baseCount: 0 });
      }
      renderBuckets();
      elements.bucketSelect.value = value;
      showAlert(`Using new bucket '${value}'.`, "info");
    });

    elements.assignBtn.addEventListener("click", assignSelected);
    elements.undoBtn.addEventListener("click", undoLast);
    elements.saveBtn.addEventListener("click", saveChanges);
    elements.reloadBtn.addEventListener("click", reloadFromDisk);
  }

  function showAlert(message, type = "info") {
    const toast = document.createElement("div");
    toast.className = `toast fade show shadow-sm border-0 text-bg-${mapToastType(type)}`;
    toast.role = "status";
    toast.innerHTML = `
      <div class="d-flex align-items-center">
        <div class="toast-body">${message}</div>
        <button type="button" class="btn-close btn-close-white me-2 m-auto" aria-label="Close"></button>
      </div>`;

    const closeBtn = toast.querySelector(".btn-close");
    closeBtn.addEventListener("click", () => toast.remove());

    elements.toastContainer.appendChild(toast);
    setTimeout(() => {
      toast.classList.remove("show");
      setTimeout(() => toast.remove(), 300);
    }, 3500);
  }

  function toggleBusy(button, busy) {
    if (!button) return;
    button.disabled = busy;
    if (busy) {
      button.dataset.originalText = button.textContent;
      button.innerHTML = `<span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>${button.dataset.loadingLabel || button.textContent}`;
    } else if (button.dataset.originalText) {
      button.textContent = button.dataset.originalText;
      delete button.dataset.originalText;
    }
  }

  function findAssignmentMismatch(expected, actual) {
    const expectedSet = new Set(expected);
    const actualSet = new Set(actual);
    const missing = expected.filter((kw) => !actualSet.has(kw));
    const unexpected = actual.filter((kw) => !expectedSet.has(kw));

    if (missing.length === 0 && unexpected.length === 0) return "";

    const parts = [];
    if (missing.length) parts.push(`Missing: ${missing.join(", ")}`);
    if (unexpected.length) parts.push(`Unexpected: ${unexpected.join(", ")}`);
    return parts.join(" | ");
  }

  function buildAssignmentMessage(assigned, alreadyMapped, bucket) {
    const assignedList = assigned.length ? assigned.join(", ") : "No new keywords";
    const alreadyList = alreadyMapped.length ? ` (already existed: ${alreadyMapped.join(", ")})` : "";
    return `Assigned ${assigned.length} keyword(s) to ${bucket}: ${assignedList}${alreadyList}`;
  }

  function mapToastType(type) {
    switch (type) {
      case "success":
      case "danger":
      case "warning":
      case "info":
        return type;
      default:
        return "secondary";
    }
  }

  bindEvents();
  fetchState();
})();
