from __future__ import annotations

import argparse
import sys
import time

from ingest.config import load_config
from ingest.importer import run_import

# Official OpenAI pricing as of 2026-09-13 (https://platform.openai.com/docs/pricing),
# USD per 1M tokens. Only accurate for the default text-embedding-3-small —
# if OPENAI_EMBEDDING_MODEL is overridden, this constant no longer applies
# and the printed cost is not meaningful.
TEXT_EMBEDDING_3_SMALL_PRICE_PER_1M_TOKENS = 0.02


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="repair-ingest", description="Repair V2 catalogue ingestion")
    sub = parser.add_subparsers(dest="command", required=True)

    import_parser = sub.add_parser("import", help="Import the vehicle catalogue")
    import_parser.add_argument(
        "--namespace",
        choices=["all", "csv", "legacy_sql"],
        default="all",
        help="Which source namespace(s) to ingest",
    )

    knowledge_parser = sub.add_parser("ingest-knowledge", help="Embed and store the Markdown knowledge corpus")
    knowledge_parser.add_argument(
        "--dry-run", action="store_true", help="Parse and chunk without calling OpenAI or writing embeddings"
    )

    moto_knowledge_parser = sub.add_parser(
        "ingest-motorcycle-knowledge", help="Embed and store the knowledge/motorcycles Markdown corpus"
    )
    moto_knowledge_parser.add_argument(
        "--dry-run", action="store_true", help="Parse and chunk without calling OpenAI or writing embeddings"
    )
    moto_knowledge_parser.add_argument(
        "--prune", action="store_true",
        help="Also delete documents whose source file no longer exists on disk (off by default)"
    )

    args = parser.parse_args(argv)

    if args.command == "import":
        config = load_config()
        namespaces = None if args.namespace == "all" else {args.namespace}
        started = time.monotonic()
        stats = run_import(config, namespaces=namespaces)
        elapsed = time.monotonic() - started
        print(f"Import completed in {elapsed:.1f}s")
        print(f"  files discovered:     {stats.files_discovered}")
        print(f"  raw records ingested: {stats.records_ingested}")
        print(f"  records skipped:      {stats.records_skipped}")
        print(f"  variants created:     {stats.variants_created}")
        print(f"  variants unchanged:   {stats.variants_existing}")
        print(f"  quality issues found: {stats.quality_issues}")
        return 0

    if args.command == "ingest-knowledge":
        from embeddings.ingest_knowledge import ingest_knowledge_corpus

        config = load_config()
        started = time.monotonic()
        stats = ingest_knowledge_corpus(config, dry_run=args.dry_run)
        elapsed = time.monotonic() - started
        print(f"Knowledge ingestion completed in {elapsed:.1f}s")
        print(f"  documents discovered:  {stats.documents_discovered}")
        print(f"  documents unchanged:   {stats.documents_unchanged}")
        print(f"  documents (re)ingested:{stats.documents_reingested}")
        print(f"  chunks written:        {stats.chunks_written}")
        print(f"  embedding errors:      {stats.embedding_errors}")
        if stats.embedding_tokens_used > 0:
            cost = stats.embedding_tokens_used / 1_000_000 * TEXT_EMBEDDING_3_SMALL_PRICE_PER_1M_TOKENS
            print(f"  embedding tokens used: {stats.embedding_tokens_used} (measured, reported by the API)")
            print(f"  estimated cost:        ${cost:.6f} (at text-embedding-3-small's $0.02/1M rate)")
        else:
            print("  embedding tokens used: 0 (no new embedding calls were made this run)")
        return 0

    if args.command == "ingest-motorcycle-knowledge":
        from embeddings.ingest_motorcycle_knowledge import ingest_motorcycle_knowledge

        config = load_config()
        started = time.monotonic()
        stats = ingest_motorcycle_knowledge(config, dry_run=args.dry_run, prune=args.prune)
        elapsed = time.monotonic() - started
        print(f"Motorcycle knowledge ingestion completed in {elapsed:.1f}s")
        print(f"  documents discovered:  {stats.documents_discovered}")
        print(f"  documents unchanged:   {stats.documents_unchanged}")
        print(f"  documents (re)ingested:{stats.documents_reingested}")
        print(f"  documents failed:      {stats.documents_failed}")
        print(f"  documents pruned:      {stats.documents_pruned}")
        print(f"  chunks written:        {stats.chunks_written}")
        print(f"  facts written:         {stats.facts_written}")
        print(f"  embedding errors:      {stats.embedding_errors}")
        if stats.embedding_tokens_used > 0:
            cost = stats.embedding_tokens_used / 1_000_000 * TEXT_EMBEDDING_3_SMALL_PRICE_PER_1M_TOKENS
            print(f"  embedding tokens used: {stats.embedding_tokens_used} (measured, reported by the API)")
            print(f"  estimated cost:        ${cost:.6f} (at text-embedding-3-small's $0.02/1M rate)")
        else:
            print("  embedding tokens used: 0 (no new embedding calls were made this run)")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
