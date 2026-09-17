#!/usr/bin/env python3
"""Structural sanity for Java sources without a JDK: comment/string-stripped bracket balance.

This is not a parser and cannot prove anything compiles — it catches the class of damage a
bulk edit does silently (a brace lost by a replacement, a heredoc that ate a closing paren),
which is worth a second of CPU when no javac is available.
"""
import glob
import re
import sys

PAIRS = {')': '(', ']': '[', '}': '{'}
OPEN = set(PAIRS.values())


def strip(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            # keep the newline count so the reported line numbers stay honest
            out.append("\n" * src.count("\n", i, j))
            i = j
            continue
        if c == '"':
            # text blocks and escapes both end at an unescaped quote
            triple = src[i:i + 3] == '"""'
            j = i + (3 if triple else 1)
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if triple and src[j:j + 3] == '"""':
                    j += 3
                    break
                if not triple and src[j] == '"':
                    j += 1
                    break
                j += 1
            i = j
            out.append('""' + "\n" * src.count("\n", i, j))
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
            out.append("''")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main() -> int:
    bad = 0
    files = sorted(glob.glob("src/**/*.java", recursive=True)) + sorted(
        glob.glob("tools/*.java")) + sorted(glob.glob("*.java"))
    for f in files:
        src = strip(open(f, encoding="utf-8").read())
        stack = []
        problem = None
        for line_no, line in enumerate(src.split("\n"), 1):
            for ch in line:
                if ch in OPEN:
                    stack.append((ch, line_no))
                elif ch in PAIRS:
                    if not stack:
                        problem = f"line ~{line_no}: stray '{ch}' with nothing open"
                        break
                    op, _ = stack.pop()
                    if op != PAIRS[ch]:
                        problem = f"line ~{line_no}: '{ch}' closes a '{op}'"
                        break
            if problem:
                break
        if not problem and stack:
            op, ln = stack[-1]
            problem = f"'{op}' opened at line ~{ln} never closed"
        if problem:
            bad += 1
            print(f"FAIL  {f}: {problem}")
        if re.search(r";;", src) and not problem:
            print(f"note  {f}: double semicolon")
    print(f"{len(files)} files checked, {bad} unbalanced")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
