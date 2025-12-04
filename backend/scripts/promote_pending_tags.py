#!/usr/bin/env python3
"""Batch-tool for promoting pending taxonomy keywords into custom taxonomy.

The script lets you:
    - inspect pending keywords in a paginated list;
    - assign several keywords at once to any existing bucket;
    - create a brand new bucket on the fly and fill it immediately;
    - undo the last assignment batch before saving; and
    - write changes only when you explicitly save (with automatic backups).

Usage:
    python scripts/promote_pending_tags.py

While running, type one of the single-letter commands shown in the prompt.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Optional

from taxonomy_manager.manager import TaxonomyManager


BACKEND_ROOT = Path(__file__).resolve().parents[1]


def _parse_selection(selection: str, max_index: int) -> List[int]:
    selection = selection.strip().lower()
    if not selection:
        raise ValueError("No selection provided")
    if selection in {"all", "*"}:
        return list(range(max_index))

    result: List[int] = []
    tokens = re.split(r"[\s,]+", selection)
    for token in tokens:
        if not token:
            continue
        if "-" in token:
            start_str, end_str = token.split("-", 1)
            if not (start_str.isdigit() and end_str.isdigit()):
                raise ValueError(f"Invalid range token: {token}")
            start = int(start_str)
            end = int(end_str)
            if start > end:
                start, end = end, start
            for idx in range(start, end + 1):
                _add_index(idx, max_index, result)
        else:
            if not token.isdigit():
                raise ValueError(f"Invalid token: {token}")
            _add_index(int(token), max_index, result)

    unique = sorted(set(i - 1 for i in result))
    if any(idx < 0 or idx >= max_index for idx in unique):
        raise ValueError("Selection contains out-of-range indices")
    return unique


def _add_index(value: int, max_index: int, bucket: List[int]):
    if value < 1 or value > max_index:
        raise ValueError(f"Selection value {value} outside 1-{max_index}")
    bucket.append(value)


class PendingPromoter:
    def __init__(self):
        self.manager = TaxonomyManager(BACKEND_ROOT)

    def run(self) -> int:
        if not self.manager.list_pending():
            print("✅ No pending taxonomy keywords to process.")
            return 0

        print("🧭 Batch taxonomy promoter ready. Type 'h' for help.")
        while True:
            self._print_status()
            command = input("[L]ist  [A]ssign  [U]ndo  [S]ave  [H]elp  [Q]uit > ").strip().lower()
            if command in {"l", "list"}:
                self._cmd_list()
            elif command in {"a", "assign"}:
                self._cmd_assign()
            elif command in {"u", "undo"}:
                self._cmd_undo()
            elif command in {"s", "save"}:
                if self._cmd_save():
                    return 0
            elif command in {"h", "help"}:
                self._print_help()
            elif command in {"q", "quit", "exit"}:
                if self._confirm_discard():
                    print("👋 Goodbye. No changes were written.")
                    return 0
            else:
                print("❓ Unknown command. Type 'h' for help.")

    # ------------------------------------------------------------------
    # Command handlers
    # ------------------------------------------------------------------
    def _cmd_list(self):
        pending = self.manager.list_pending()
        if not pending:
            print("✅ Pending queue empty.")
            return

        try:
            limit_str = input("How many keywords to show (default 20)? ").strip()
            limit = int(limit_str) if limit_str else 20
            if limit <= 0:
                raise ValueError
        except ValueError:
            print("❌ Please enter a positive integer.")
            return

        filter_term = input("Optional filter text (press Enter to skip): ").strip().lower()

        matches = [
            (idx, entry["keyword"])
            for idx, entry in enumerate(pending, start=1)
            if not filter_term or filter_term in entry["keyword"].lower()
        ]

        if not matches:
            print("🔍 No pending keywords matched that filter.")
            return

        print("\nPending keywords:")
        for idx, keyword in matches[:limit]:
            print(f"  {idx:>4}: {keyword}")

        remaining = len(matches) - min(limit, len(matches))
        if remaining > 0:
            print(f"  ... {remaining} more not shown")
        print()

    def _cmd_assign(self):
        if not self.manager.list_pending():
            print("✅ Pending queue empty.")
            return

        self._print_bucket_overview()
        bucket = self._prompt_bucket()
        if not bucket:
            return

        self._show_pending_snapshot()
        target_indices = self._prompt_indices(len(self.manager.list_pending()))
        if not target_indices:
            print("ℹ️  No indices selected. Assignment cancelled.")
            return

        op = self.manager.assign_keywords(bucket, target_indices)
        keywords = op["keywords"]
        newly_added = op["newly_added"]
        already_mapped = op["already_mapped"]

        print(f"✅ Assigned {len(keywords)} keyword(s) to '{bucket}'.")
        if newly_added:
            print(f"   • Newly added: {len(newly_added)}")
        if already_mapped:
            print(f"   • Already mapped (just cleared from pending): {len(already_mapped)}")

    def _cmd_undo(self):
        op = self.manager.undo()
        if not op:
            print("ℹ️  Nothing to undo.")
            return

        print(f"↩️  Undid assignment of {len(op['entries'])} keyword(s) from '{op['bucket']}'.")

    def _cmd_save(self) -> bool:
        if not self.manager.has_changes():
            print("ℹ️  No changes to save.")
            return False

        summary = self.manager.summary()

        print("\nPending changes summary:")
        print(f"   • Pending keywords cleared: {summary['mapped']}")
        print(f"   • Brand new mappings added: {summary['newly_added']}")

        confirm = input("Write updates? (yes/no): ").strip().lower()
        if confirm not in {"y", "yes"}:
            print("❌ Save cancelled.")
            return False

        self.manager.save()
        print("💾 Changes saved. Backups created alongside originals.")
        return True

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------
    def _prompt_bucket(self) -> Optional[str]:
        while True:
            choice = input("Bucket name (/? to search, + to create new, Enter to cancel): ").strip()
            if choice == "":
                return None
            if choice == "+":
                new_bucket = input("New bucket name: ").strip()
                if not new_bucket:
                    print("❌ Bucket name cannot be empty.")
                    continue
                return new_bucket
            if choice == "/":
                self._search_buckets()
                continue
            if choice == "?":
                self._search_buckets()
                continue

            bucket_order = self.manager.state.bucket_order
            if choice.isdigit():
                index = int(choice) - 1
                if 0 <= index < len(bucket_order):
                    return bucket_order[index]
                print(f"❌ Number out of range (1-{len(bucket_order)}).")
                continue

            # direct match
            if choice in bucket_order:
                return choice

            # maybe partial match
            matches = [bucket for bucket in bucket_order if choice.lower() in bucket.lower()]
            if len(matches) == 1:
                print(f"ℹ️  Using bucket '{matches[0]}' (unique match).")
                return matches[0]
            if matches:
                print("Multiple matches found:")
                for idx, bucket in enumerate(matches, start=1):
                    print(f"  {idx:>2}. {bucket}")
                continue

            print("❌ Bucket not found. Type '?' to search or '+' to create new.")

    def _search_buckets(self):
        bucket_order = self.manager.state.bucket_order
        term = input("Search text (empty to show first 20 buckets): ").strip().lower()
        matches = [bucket for bucket in bucket_order if not term or term in bucket.lower()]
        print("\nAvailable buckets:")
        if not matches:
            print("  (no matches)")
        else:
            for idx, bucket in enumerate(matches[:50], start=1):
                print(f"  {idx:>2}. {bucket}")
            if len(matches) > 50:
                print(f"  ... {len(matches) - 50} more not shown")
        print()

    def _prompt_indices(self, max_index: int) -> List[int]:
        print("Type keyword numbers separated by commas (e.g. 1,2,5-8) or 'all'.")
        selection = input("Selection: ")
        try:
            indices = _parse_selection(selection, max_index)
        except ValueError as exc:
            print(f"❌ {exc}")
            return []
        return indices

    def _print_bucket_overview(self):
        base_taxonomy = self.manager.state.base_taxonomy
        custom_taxonomy = self.manager.state.custom_taxonomy
        bucket_order = self.manager.state.bucket_order

        print("\nAvailable buckets (number of keywords in custom/base):")
        for idx, bucket in enumerate(bucket_order, start=1):
            custom_count = len(custom_taxonomy.get(bucket, []))
            base_count = len(base_taxonomy.get(bucket, []))
            if base_count and not custom_count:
                summary = f"{base_count} base"
            elif custom_count and not base_count:
                summary = f"{custom_count} custom"
            else:
                summary = f"{custom_count} custom / {base_count} base"
            print(f"  {idx:>3}. {bucket} ({summary})")
        print()

    def _show_pending_snapshot(self, limit: int = 30):
        pending = self.manager.list_pending()
        if not pending:
            print("✅ Pending queue empty.")
            return

        print(f"\nPending keywords (showing up to {limit}):")
        for idx, entry in enumerate(pending[:limit], start=1):
            print(f"  {idx:>4}: {entry['keyword']}")
        remaining = len(pending) - min(limit, len(pending))
        if remaining > 0:
            print(f"  ... {remaining} more not shown")
        print()

    def _print_status(self):
        summary = self.manager.summary()
        print(
            f"\n📌 Pending: {summary['pending']} | Assigned this session: {summary['mapped']} | "
            f"Buckets: {summary['buckets']}"
        )

    def _print_help(self):
        print(
            """
Commands:
    L / list   - show pending keywords (optionally filtered) with indices
    A / assign - review all buckets, pick one, preview 30 keywords, then batch assign
    U / undo   - revert the most recent assignment batch
    S / save   - write taxonomy-custom.json & taxonomy-pending.jsonl (with backups)
    Q / quit   - exit without saving (asks for confirmation)

Tips:
  • When prompted for a bucket, enter a number, exact name, partial match, or '+'.
  • Use ranges like '3-10' or '1,5,7-9' for keyword selection; 'all' selects everything.
  • Undo only affects batches from this run; backups are created on save for safety.
            """
        )

    def _confirm_discard(self) -> bool:
        if not self.manager.has_changes():
            return True
        confirm = input("Discard unsaved changes? (yes/no): ").strip().lower()
        return confirm in {"y", "yes"}


def main() -> int:
    manager = PendingPromoter()
    return manager.run()


if __name__ == "__main__":
    sys.exit(main())
