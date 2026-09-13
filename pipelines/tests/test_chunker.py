from embeddings.chunker import chunk_markdown_body


def test_chunk_by_heading_produces_one_chunk_per_short_section():
    body = """# Title

Intro paragraph.

## Step 1

Do the first thing.

## Step 2

Do the second thing.
"""
    chunks = chunk_markdown_body(body)
    headings = [c.heading for c in chunks]
    assert "Step 1" in headings
    assert "Step 2" in headings
    assert "Title" in headings


def test_long_section_is_split_with_overlap():
    long_text = " ".join(f"word{i}" for i in range(500))
    body = f"## Long Section\n\n{long_text}\n"
    chunks = chunk_markdown_body(body)
    assert len(chunks) > 1
    assert all(c.heading == "Long Section" for c in chunks)
    # Overlap: the end of chunk 1 should reappear near the start of chunk 2.
    first_words = chunks[0].content.split()
    second_words = chunks[1].content.split()
    assert first_words[-1] in second_words[:40]


def test_section_path_reflects_heading_nesting():
    body = "# A\n\n## B\n\ncontent here\n"
    chunks = chunk_markdown_body(body)
    b_chunk = next(c for c in chunks if c.heading == "B")
    assert b_chunk.section_path == "A > B"
