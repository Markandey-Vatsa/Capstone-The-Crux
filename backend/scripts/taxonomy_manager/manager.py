"""Shared taxonomy management utilities for CLI and web tools."""

from __future__ import annotations

import json
import os
import re
import shutil
from collections import OrderedDict
from copy import deepcopy
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from difflib import SequenceMatcher
from typing import Dict, Iterable, List, Optional


@dataclass
class TaxonomyState:
    base_taxonomy: OrderedDict
    custom_taxonomy: OrderedDict
    pending_keywords: List[Dict[str, str]]
    history: List[Dict] = field(default_factory=list)
    bucket_order: List[str] = field(default_factory=list)

    original_custom: OrderedDict | None = None
    original_pending: List[Dict[str, str]] | None = None


class TaxonomyManager:
    """Manage taxonomy files and provide batching operations."""

    def __init__(self, backend_root: Path):
        self.backend_root = backend_root
        resource_dir = backend_root / "src" / "main" / "resources" / "taxonomy"

        self.base_taxonomy_path = resource_dir / "base-taxonomy.json"
        self.custom_taxonomy_path = backend_root / "taxonomy-custom.json"
        self.pending_path = backend_root / os.getenv(
            "TAXONOMY_PENDING_FILE", "taxonomy-pending.jsonl"
        )
        self.backups_dir = backend_root / "taxonomy_backups"

        base = self._load_json(self.base_taxonomy_path, OrderedDict())
        custom = self._load_json(self.custom_taxonomy_path, OrderedDict())
        pending = self._load_pending(self.pending_path)

        bucket_order = list(
            OrderedDict.fromkeys(list(base.keys()) + list(custom.keys()))
        )

        self.state = TaxonomyState(
            base_taxonomy=base,
            custom_taxonomy=custom,
            pending_keywords=pending,
            bucket_order=bucket_order,
            original_custom=deepcopy(custom),
            original_pending=deepcopy(pending),
        )

    # ------------------------------------------------------------------
    # File helpers
    # ------------------------------------------------------------------
    def _load_json(self, path: Path, default):
        if not path.exists():
            return default
        with path.open("r", encoding="utf-8") as fh:
            return json.load(fh, object_pairs_hook=OrderedDict)

    def _load_pending(self, path: Path) -> List[Dict[str, str]]:
        if not path.exists():
            return []
        entries: List[Dict[str, str]] = []
        with path.open("r", encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line:
                    continue
                try:
                    payload = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(payload, dict) and payload.get("keyword"):
                    entries.append({"keyword": payload["keyword"].strip()})
        return entries

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def list_pending(self) -> List[Dict[str, str]]:
        return self.state.pending_keywords

    def list_buckets(self) -> List[str]:
        return self.state.bucket_order

    def suggest_buckets(self, keyword: str, top_n: int = 3, min_score: float = 30.0) -> List[Dict[str, str]]:
        keyword = (keyword or "").strip()
        if not keyword:
            return []

        key_lower = keyword.lower()
        key_tokens = _tokenize(keyword)

        scores: Dict[str, Dict[str, object]] = {}

        for bucket, entry_keyword, source in self._iter_keywords():
            candidate_lower = entry_keyword.lower()
            candidate_tokens = _tokenize(entry_keyword)

            score = 0.0
            reasons: List[str] = []

            if key_lower == candidate_lower:
                score += 100.0
                reasons.append("exact match")

            shared_tokens = key_tokens & candidate_tokens
            if shared_tokens:
                token_score = min(len(shared_tokens) * 20.0, 60.0)
                score += token_score
                reasons.append(f"token overlap: {', '.join(sorted(shared_tokens))}")

            if key_lower in candidate_lower or candidate_lower in key_lower:
                score += 25.0
                reasons.append("substring match")

            if score < 100.0:
                fuzzy = SequenceMatcher(None, key_lower, candidate_lower).ratio() * 30.0
                if fuzzy >= 10.0:
                    score += fuzzy
                    reasons.append(f"fuzzy {fuzzy:.1f}")

            if score <= 0:
                continue

            bucket_entry = scores.get(bucket)
            if not bucket_entry or score > bucket_entry["score"]:
                scores[bucket] = {
                    "bucket": bucket,
                    "score": score,
                    "source": source,
                    "matched_keyword": entry_keyword,
                    "reasons": reasons,
                }

        ranked = sorted(scores.values(), key=lambda item: item["score"], reverse=True)
        return [
            {
                "bucket": item["bucket"],
                "score": round(item["score"], 1),
                "source": item["source"],
                "matched_keyword": item["matched_keyword"],
                "reasons": item["reasons"],
            }
            for item in ranked[:top_n]
            if item["score"] >= min_score
        ]

    def _iter_keywords(self) -> Iterable[tuple[str, str, str]]:
        yield from _keyword_sources(self.state)

    def assign_keywords(self, bucket: str, indices: Iterable[int]) -> Dict[str, List[str]]:
        """Assign keywords (indices in current pending list) to bucket."""
        pending = self.state.pending_keywords
        custom = self.state.custom_taxonomy
        base = self.state.base_taxonomy

        indices = sorted(set(indices))
        if any(idx < 0 or idx >= len(pending) for idx in indices):
            raise IndexError("Pending index out of range")

        keywords = [pending[idx]["keyword"] for idx in indices]

        created_bucket = False
        if bucket not in custom and bucket not in base:
            custom[bucket] = []
            self.state.bucket_order.append(bucket)
            created_bucket = True

        bucket_list = custom.setdefault(bucket, [])
        already_present = set(
            k.lower()
            for k in bucket_list + base.get(bucket, [])
            if isinstance(k, str)
        )

        newly_added: List[str] = []
        already_mapped: List[str] = []

        for keyword in keywords:
            lower = keyword.lower()
            if lower in already_present:
                already_mapped.append(keyword)
                continue
            bucket_list.append(keyword)
            already_present.add(lower)
            newly_added.append(keyword)

        removed_entries = [pending[idx] for idx in indices]
        for idx in sorted(indices, reverse=True):
            del pending[idx]

        op = {
            "bucket": bucket,
            "keywords": keywords,
            "newly_added": newly_added,
            "already_mapped": already_mapped,
            "entries": removed_entries,
            "created_bucket": created_bucket,
        }
        self.state.history.append(op)

        return op

    def undo(self) -> Optional[Dict]:
        if not self.state.history:
            return None

        last = self.state.history.pop()
        bucket = last["bucket"]
        entries = last["entries"]
        newly_added = last["newly_added"]

        self.state.pending_keywords.extend(entries)

        bucket_list = self.state.custom_taxonomy.get(bucket, [])
        if bucket_list:
            bucket_list[:] = [kw for kw in bucket_list if kw not in newly_added]
            if not bucket_list and bucket in self.state.custom_taxonomy:
                if last["created_bucket"] or bucket not in (self.state.original_custom or {}):
                    del self.state.custom_taxonomy[bucket]
                    if bucket in self.state.bucket_order and last["created_bucket"]:
                        self.state.bucket_order.remove(bucket)

        return last

    def has_changes(self) -> bool:
        return bool(self.state.history) or (
            self.state.custom_taxonomy != (self.state.original_custom or OrderedDict())
            or self.state.pending_keywords != (self.state.original_pending or [])
        )

    def summary(self) -> Dict[str, int]:
        return {
            "pending": len(self.state.pending_keywords),
            "mapped": sum(len(op["keywords"]) for op in self.state.history),
            "newly_added": sum(len(op["newly_added"]) for op in self.state.history),
            "buckets": len(self.state.bucket_order),
        }

    def save(self) -> None:
        if not self.has_changes():
            return

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.backups_dir.mkdir(parents=True, exist_ok=True)

        for path in (self.custom_taxonomy_path, self.pending_path):
            if path.exists():
                backup_name = f"{path.stem}__{timestamp}{path.suffix}.bak"
                backup_path = self.backups_dir / backup_name
                shutil.copy2(path, backup_path)

        for bucket, keywords in list(self.state.custom_taxonomy.items()):
            cleaned = _normalise_keywords(keywords)
            if cleaned:
                self.state.custom_taxonomy[bucket] = cleaned
            else:
                del self.state.custom_taxonomy[bucket]
                if (
                    bucket not in self.state.base_taxonomy
                    and bucket in self.state.bucket_order
                ):
                    self.state.bucket_order.remove(bucket)

        with self.custom_taxonomy_path.open("w", encoding="utf-8") as fh:
            json.dump(self.state.custom_taxonomy, fh, indent=2, ensure_ascii=False)
            fh.write("\n")

        if self.state.pending_keywords:
            with self.pending_path.open("w", encoding="utf-8") as fh:
                for entry in self.state.pending_keywords:
                    fh.write(json.dumps(entry, ensure_ascii=False))
                    fh.write("\n")
        elif self.pending_path.exists():
            self.pending_path.unlink()

        self.state.original_custom = deepcopy(self.state.custom_taxonomy)
        self.state.original_pending = deepcopy(self.state.pending_keywords)
        self.state.history.clear()
        self._prune_backups()


    def _prune_backups(self, keep: int = 10) -> None:
        if not self.backups_dir.exists():
            return

        grouped: Dict[str, List[Path]] = {}
        for backup in sorted(
            self.backups_dir.glob("*.bak"), key=lambda p: p.stat().st_mtime, reverse=True
        ):
            prefix = backup.name.split("__", 1)[0]
            grouped.setdefault(prefix, []).append(backup)

        for backups in grouped.values():
            for old in backups[keep:]:
                try:
                    old.unlink()
                except FileNotFoundError:
                    continue


def _normalise_keywords(keywords: Iterable[str]) -> List[str]:
    seen = OrderedDict()
    for keyword in keywords:
        if not isinstance(keyword, str):
            continue
        cleaned = keyword.strip()
        if cleaned:
            seen.setdefault(cleaned, None)
    return list(seen.keys())


TOKEN_PATTERN = re.compile(r"[a-z0-9]+", re.IGNORECASE)


def _tokenize(value: str) -> set[str]:
    return {
        token.lower()
        for token in TOKEN_PATTERN.findall(value)
        if len(token) >= 3
    }


def _keyword_sources(state: TaxonomyState) -> Iterable[tuple[str, str, str]]:
    for bucket, keywords in state.base_taxonomy.items():
        for keyword in keywords or []:
            if isinstance(keyword, str):
                yield bucket, keyword, "base"
    for bucket, keywords in state.custom_taxonomy.items():
        for keyword in keywords or []:
            if isinstance(keyword, str):
                yield bucket, keyword, "custom"
