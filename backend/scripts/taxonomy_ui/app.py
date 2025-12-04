from __future__ import annotations

import sys
from math import ceil
from pathlib import Path
from typing import Any, Dict, List

from flask import Flask, jsonify, render_template, request

APP_ROOT = Path(__file__).resolve().parent
SCRIPTS_ROOT = APP_ROOT.parent
BACKEND_ROOT = APP_ROOT.parents[1]

if str(SCRIPTS_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_ROOT))

from taxonomy_manager.manager import TaxonomyManager

app = Flask(
    __name__,
    template_folder=str(APP_ROOT / "templates"),
    static_folder=str(APP_ROOT / "static"),
)

taxonomy_manager = TaxonomyManager(BACKEND_ROOT)


def _clamp_page(value: int, total_pages: int) -> int:
    if total_pages <= 0:
        return 1
    return max(1, min(value, total_pages))


def build_state(page: int = 1, page_size: int = 50) -> Dict[str, Any]:
    page_size = max(1, min(page_size, 500))

    pending_all = taxonomy_manager.list_pending()
    total_pending = len(pending_all)
    total_pages = ceil(total_pending / page_size) if total_pending else 1
    page = _clamp_page(page, total_pages if total_pending else 1)

    start = (page - 1) * page_size
    end = start + page_size

    pending_data: List[Dict[str, Any]] = []
    for idx, entry in enumerate(pending_all[start:end], start=start):
        keyword = entry.get("keyword", "")
        pending_data.append(
            {
                "index": idx,
                "keyword": keyword,
                "suggestions": taxonomy_manager.suggest_buckets(keyword),
            }
        )

    buckets: List[Dict[str, Any]] = []
    base = taxonomy_manager.state.base_taxonomy
    custom = taxonomy_manager.state.custom_taxonomy
    for bucket in taxonomy_manager.list_buckets():
        buckets.append(
            {
                "name": bucket,
                "customCount": len(custom.get(bucket, [])),
                "baseCount": len(base.get(bucket, [])),
            }
        )

    summary = taxonomy_manager.summary()
    summary["hasChanges"] = taxonomy_manager.has_changes()

    meta = {
        "page": page if total_pending else 1,
        "pageSize": page_size,
        "totalPages": total_pages if total_pending else 1,
        "totalPending": total_pending,
    }

    return {
        "pending": pending_data,
        "buckets": buckets,
        "summary": summary,
        "meta": meta,
    }


@app.route("/")
def index():
    return render_template("index.html")


def _parse_positive_int(raw: str | None, default: int) -> int:
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


@app.route("/api/state", methods=["GET"])
def api_state():
    page = _parse_positive_int(request.args.get("page"), 1)
    page_size = _parse_positive_int(request.args.get("page_size"), 50)
    return jsonify(build_state(page=page, page_size=page_size))


@app.route("/api/assign", methods=["POST"])
def api_assign():
    payload = request.get_json(force=True, silent=True) or {}
    bucket = (payload.get("bucket") or "").strip()
    indices = payload.get("indices")
    if not bucket:
        return jsonify({"error": "Bucket name is required."}), 400
    if not isinstance(indices, list) or not indices:
        return jsonify({"error": "Indices must be a non-empty list."}), 400

    try:
        op = taxonomy_manager.assign_keywords(bucket, indices)
    except IndexError:
        return jsonify({"error": "One or more indices are out of range."}), 400
    except Exception as exc:  # pragma: no cover - defensive
        return jsonify({"error": str(exc)}), 500

    return jsonify({"operation": op, "summary": taxonomy_manager.summary()})


@app.route("/api/undo", methods=["POST"])
def api_undo():
    op = taxonomy_manager.undo()
    if not op:
        return jsonify({"error": "Nothing to undo."}), 400
    return jsonify({"operation": op, "summary": taxonomy_manager.summary()})


@app.route("/api/save", methods=["POST"])
def api_save():
    taxonomy_manager.save()
    return jsonify({"summary": taxonomy_manager.summary()})


@app.route("/api/reload", methods=["POST"])
def api_reload():
    global taxonomy_manager
    taxonomy_manager = TaxonomyManager(BACKEND_ROOT)
    page = _parse_positive_int(request.args.get("page"), 1)
    page_size = _parse_positive_int(request.args.get("page_size"), 50)
    return jsonify(build_state(page=page, page_size=page_size))


if __name__ == "__main__":
    app.run(debug=True, port=5000)
