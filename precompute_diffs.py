#!/usr/bin/env python3
"""
Precompute unified diffs for all files in lhs/ vs rhs/ and write the result
to ``precomputed_diffs.json`` in the same directory as the input folders.

The output format mirrors the ITextDiff / DiffHunk / DiffLine structures
expected by the GitHub Desktop folder-compare UI so it can be loaded
directly without running the in-process Myers diff.

Usage
-----
    python precompute_diffs.py <files_directory>

where *files_directory* contains ``lhs/`` and ``rhs/`` sub-directories.
The output file ``precomputed_diffs.json`` is written into *files_directory*.
"""

import difflib
import json
import os
import re
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Line-level Myers diff (delegated to difflib.SequenceMatcher)
# ---------------------------------------------------------------------------

def compute_unified_hunks(
    before_lines: List[str],
    after_lines: List[str],
    context: int = 3,
) -> List[Dict[str, Any]]:
    """Return a list of hunk dicts from *before_lines* → *after_lines*."""
    matcher = difflib.SequenceMatcher(None, before_lines, after_lines)
    opcodes = matcher.get_opcodes()

    # Group opcodes into hunks with context, merging nearby changes.
    groups = _group_opcodes(opcodes, context)

    hunks: List[Dict[str, Any]] = []
    patch_line = 1  # 1-based unified diff line counter

    for group in groups:
        lines: List[Dict[str, Any]] = []
        old_start: Optional[int] = None
        new_start: Optional[int] = None
        old_count = 0
        new_count = 0

        for tag, i1, i2, j1, j2 in group:
            if tag == "equal":
                for i, j in zip(range(i1, i2), range(j1, j2)):
                    if old_start is None:
                        old_start = i + 1
                    if new_start is None:
                        new_start = j + 1
                    old_count += 1
                    new_count += 1
            elif tag == "delete":
                for i in range(i1, i2):
                    if old_start is None:
                        old_start = i + 1
                    old_count += 1
            elif tag == "insert":
                for j in range(j1, j2):
                    if new_start is None:
                        new_start = j + 1
                    new_count += 1
            elif tag == "replace":
                for i in range(i1, i2):
                    if old_start is None:
                        old_start = i + 1
                    old_count += 1
                for j in range(j1, j2):
                    if new_start is None:
                        new_start = j + 1
                    new_count += 1

        if old_start is None:
            old_start = 1 if old_count > 0 else 0
        if new_start is None:
            new_start = 1 if new_count > 0 else 0

        header = {
            "oldStartLine": old_start,
            "oldLineCount": old_count,
            "newStartLine": new_start,
            "newLineCount": new_count,
        }
        hunk_text = f"@@ -{old_start},{old_count} +{new_start},{new_count} @@"

        # Hunk header line
        lines.append({
            "text": hunk_text,
            "type": "Hunk",
            "originalLineNumber": patch_line,
            "oldLineNumber": None,
            "newLineNumber": None,
            "noTrailingNewLine": False,
        })
        patch_line += 1

        # Content lines
        for tag, i1, i2, j1, j2 in group:
            if tag == "equal":
                for i, j in zip(range(i1, i2), range(j1, j2)):
                    lines.append({
                        "text": " " + before_lines[i],
                        "type": "Context",
                        "originalLineNumber": patch_line,
                        "oldLineNumber": i + 1,
                        "newLineNumber": j + 1,
                        "noTrailingNewLine": False,
                    })
                    patch_line += 1
            elif tag == "delete":
                for i in range(i1, i2):
                    lines.append({
                        "text": "-" + before_lines[i],
                        "type": "Delete",
                        "originalLineNumber": patch_line,
                        "oldLineNumber": i + 1,
                        "newLineNumber": None,
                        "noTrailingNewLine": False,
                    })
                    patch_line += 1
            elif tag == "insert":
                for j in range(j1, j2):
                    lines.append({
                        "text": "+" + after_lines[j],
                        "type": "Add",
                        "originalLineNumber": patch_line,
                        "oldLineNumber": None,
                        "newLineNumber": j + 1,
                        "noTrailingNewLine": False,
                    })
                    patch_line += 1
            elif tag == "replace":
                # Deletions first, then additions (same as git diff)
                for i in range(i1, i2):
                    lines.append({
                        "text": "-" + before_lines[i],
                        "type": "Delete",
                        "originalLineNumber": patch_line,
                        "oldLineNumber": i + 1,
                        "newLineNumber": None,
                        "noTrailingNewLine": False,
                    })
                    patch_line += 1
                for j in range(j1, j2):
                    lines.append({
                        "text": "+" + after_lines[j],
                        "type": "Add",
                        "originalLineNumber": patch_line,
                        "oldLineNumber": None,
                        "newLineNumber": j + 1,
                        "noTrailingNewLine": False,
                    })
                    patch_line += 1

        unified_start = lines[0]["originalLineNumber"] or 0
        unified_end = lines[-1]["originalLineNumber"] or 0

        hunks.append({
            "header": header,
            "lines": lines,
            "unifiedDiffStart": unified_start,
            "unifiedDiffEnd": unified_end,
            "expansionType": "None",
        })

    return hunks


def _group_opcodes(
    opcodes: List[Tuple[str, int, int, int, int]],
    context: int,
) -> List[List[Tuple[str, int, int, int, int]]]:
    """
    Split *opcodes* into groups separated by more than 2 * *context* equal
    lines, and trim each group to include at most *context* equal lines at
    the edges.  Identical to ``difflib.SequenceMatcher.get_grouped_opcodes``
    but reimplemented to match the exact semantics we need.
    """
    # Filter out groups that are all equal
    if all(tag == "equal" for tag, *_ in opcodes):
        return []

    groups: List[List[Tuple[str, int, int, int, int]]] = []
    current: List[Tuple[str, int, int, int, int]] = []

    for tag, i1, i2, j1, j2 in opcodes:
        if tag == "equal":
            length = i2 - i1
            if length > 2 * context:
                # Tail context for current group
                if current:
                    current.append(("equal", i1, i1 + context, j1, j1 + context))
                    groups.append(current)
                    current = []
                # Head context for next group
                current.append(("equal", i2 - context, i2, j2 - context, j2))
            else:
                current.append((tag, i1, i2, j1, j2))
        else:
            if not current:
                # No leading context yet — this is the first change
                pass
            current.append((tag, i1, i2, j1, j2))

    if current:
        # Remove trailing 'equal' that extends beyond the file
        groups.append(current)

    # Strip leading equal-only groups and trailing equal-only groups
    return [g for g in groups if not all(t == "equal" for t, *_ in g)]


# ---------------------------------------------------------------------------
# File enumeration & diff computation
# ---------------------------------------------------------------------------

HIDDEN_BIDI_RE = re.compile(r"[\u202A-\u202E]|[\u2066-\u2069]")


def split_lines(content: str) -> List[str]:
    """Split content into lines (same semantics as the TS ``splitLines``)."""
    lines = content.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return lines


def get_all_files(directory: str) -> List[str]:
    """Recursively list all files under *directory*, returning relative paths."""
    result: List[str] = []
    for root, _dirs, files in os.walk(directory):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, directory).replace("\\", "/")
            result.append(rel)
    result.sort()
    return result


def read_file_safe(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return None


def compute_diff_for_file(
    lhs_dir: str,
    rhs_dir: str,
    rel_path: str,
    status_kind: str,
) -> Optional[Dict[str, Any]]:
    """Compute the ITextDiff-shaped dict for a single file pair."""
    before_path = os.path.join(lhs_dir, rel_path)
    after_path = os.path.join(rhs_dir, rel_path)

    before_lines: List[str] = []
    after_lines: List[str] = []
    text = ""

    if status_kind == "new":
        content = read_file_safe(after_path)
        if content is None:
            return None
        after_lines = split_lines(content)
        text = content
    elif status_kind == "deleted":
        content = read_file_safe(before_path)
        if content is None:
            return None
        before_lines = split_lines(content)
    else:  # modified
        bc = read_file_safe(before_path)
        ac = read_file_safe(after_path)
        if bc is None or ac is None:
            return None
        before_lines = split_lines(bc)
        after_lines = split_lines(ac)
        text = ac

    hunks = compute_unified_hunks(before_lines, after_lines, context=3)

    max_line = 0
    has_hidden_bidi = False
    for hunk in hunks:
        for line in hunk["lines"]:
            oln = line.get("oldLineNumber")
            nln = line.get("newLineNumber")
            if oln is not None and oln > max_line:
                max_line = oln
            if nln is not None and nln > max_line:
                max_line = nln
            if not has_hidden_bidi and HIDDEN_BIDI_RE.search(line["text"]):
                has_hidden_bidi = True

    return {
        "kind": "Text",
        "text": text,
        "hunks": hunks,
        "maxLineNumber": max_line,
        "hasHiddenBidiChars": has_hidden_bidi,
    }


def file_id(rel_path: str, status_kind: str) -> str:
    """
    Build the same file-id string that the UI uses
    (WorkingDirectoryFileChange.id = ``AppFileStatusKind + '+' + path``).

    AppFileStatusKind enum values are title-cased: New, Modified, Deleted.
    """
    kind_map = {
        "new": "New",
        "deleted": "Deleted",
        "modified": "Modified",
    }
    return kind_map.get(status_kind, status_kind) + "+" + rel_path


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    base_dir = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "difftastic", "Files")
    )
    lhs_dir = os.path.join(base_dir, "lhs")
    rhs_dir = os.path.join(base_dir, "rhs")

    if not os.path.isdir(lhs_dir) or not os.path.isdir(rhs_dir):
        print(f"Error: expected lhs/ and rhs/ directories inside {base_dir}", file=sys.stderr)
        sys.exit(1)

    lhs_files = set(get_all_files(lhs_dir))
    rhs_files = set(get_all_files(rhs_dir))

    all_paths: List[Tuple[str, str]] = []  # (rel_path, status_kind)

    # Deleted: in lhs but not rhs
    for p in sorted(lhs_files - rhs_files):
        all_paths.append((p, "deleted"))

    # New: in rhs but not lhs
    for p in sorted(rhs_files - lhs_files):
        all_paths.append((p, "new"))

    # Potentially modified: in both
    for p in sorted(lhs_files & rhs_files):
        bc = read_file_safe(os.path.join(lhs_dir, p))
        ac = read_file_safe(os.path.join(rhs_dir, p))
        if bc is not None and ac is not None and bc != ac:
            all_paths.append((p, "modified"))

    print(f"Precomputing diffs for {len(all_paths)} file(s) in {base_dir} ...")

    result: Dict[str, Any] = {}  # keyed by file id

    for rel_path, status_kind in all_paths:
        fid = file_id(rel_path, status_kind)
        diff = compute_diff_for_file(lhs_dir, rhs_dir, rel_path, status_kind)
        if diff is not None:
            result[fid] = diff
            n_hunks = len(diff["hunks"])
            n_lines = sum(len(h["lines"]) for h in diff["hunks"])
            print(f"  {fid}: {n_hunks} hunk(s), {n_lines} line(s)")
        else:
            print(f"  {fid}: skipped (unreadable)")

    out_path = os.path.join(base_dir, "precomputed_diffs.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False)

    size_kb = os.path.getsize(out_path) / 1024
    print(f"Written {out_path} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
