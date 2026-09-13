"""Markdown chunking by heading, with a word-count budget fallback for any
section that runs long. Word count is used as a simple, dependency-free
proxy for token count — adequate for chunk-sizing purposes; it does not
need to match the generation model's tokenizer exactly.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

CHUNKING_VERSION = "heading-v1"
MAX_WORDS_PER_CHUNK = 220
OVERLAP_WORDS = 30

_HEADING_RE = re.compile(r"^(#{1,3})\s+(.*)$", re.MULTILINE)


@dataclass(frozen=True)
class Chunk:
    heading: str | None
    section_path: str
    content: str


def chunk_markdown_body(body: str) -> list[Chunk]:
    """Split a markdown document body into sections by heading, then split
    any section longer than MAX_WORDS_PER_CHUNK into overlapping pieces.
    """
    sections = _split_by_heading(body)
    chunks: list[Chunk] = []
    for heading_path, text in sections:
        text = text.strip()
        if not text:
            continue
        words = text.split()
        if len(words) <= MAX_WORDS_PER_CHUNK:
            chunks.append(Chunk(heading=heading_path[-1] if heading_path else None,
                                 section_path=" > ".join(heading_path),
                                 content=text))
            continue
        start = 0
        part_num = 1
        while start < len(words):
            end = min(start + MAX_WORDS_PER_CHUNK, len(words))
            piece = " ".join(words[start:end])
            heading = heading_path[-1] if heading_path else None
            path = " > ".join(heading_path) + f" (part {part_num})" if heading_path else f"(part {part_num})"
            chunks.append(Chunk(heading=heading, section_path=path, content=piece))
            if end == len(words):
                break
            start = end - OVERLAP_WORDS
            part_num += 1
    return chunks


def _split_by_heading(body: str) -> list[tuple[list[str], str]]:
    matches = list(_HEADING_RE.finditer(body))
    if not matches:
        return [([], body)]

    sections: list[tuple[list[str], str]] = []
    stack: list[tuple[int, str]] = []

    preamble = body[: matches[0].start()].strip()
    if preamble:
        sections.append(([], preamble))

    for i, match in enumerate(matches):
        level = len(match.group(1))
        title = match.group(2).strip()
        stack = [(lvl, t) for lvl, t in stack if lvl < level]
        stack.append((level, title))
        path = [t for _, t in stack]

        content_start = match.end()
        content_end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        content = body[content_start:content_end]
        sections.append((path, content))

    return sections
