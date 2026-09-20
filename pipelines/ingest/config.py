"""Runtime configuration for the ingestion CLI, read from the environment.

No secret or connection string has a hardcoded fallback pointing at a real
database; the defaults here only ever point at the local Docker Compose
stack (see docker-compose.yml).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

REPO_ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Config:
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str

    vehicles_dir: Path
    legacy_sql_path: Path
    knowledge_dir: Path
    motorcycle_knowledge_dir: Path

    openai_api_key: str | None
    embedding_model: str
    embedding_dimensions: int

    schema: str = "public"

    @property
    def dsn(self) -> str:
        base = (
            f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
            f"user={self.db_user} password={self.db_password}"
        )
        if self.schema != "public":
            base += f" options='-c search_path={self.schema},public'"
        return base


def load_config() -> Config:
    return Config(
        db_host=os.environ.get("DB_HOST", "localhost"),
        db_port=int(os.environ.get("DB_PORT", "5544")),
        db_name=os.environ.get("DB_NAME", "repair_v2"),
        db_user=os.environ.get("DB_USER", "repair_v2"),
        db_password=os.environ.get("DB_PASSWORD", "repair_v2_local_only"),
        vehicles_dir=Path(
            os.environ.get("VEHICLES_DIR", str(REPO_ROOT / "data/raw/vehicles"))
        ),
        legacy_sql_path=Path(
            os.environ.get(
                "LEGACY_SQL_PATH",
                str(REPO_ROOT / "data/raw/legacy-database/repair_db_legacy.sql"),
            )
        ),
        knowledge_dir=Path(
            os.environ.get("KNOWLEDGE_DIR", str(REPO_ROOT / "data/knowledge"))
        ),
        motorcycle_knowledge_dir=Path(
            os.environ.get("MOTORCYCLE_KNOWLEDGE_DIR", str(REPO_ROOT / "knowledge/motorcycles"))
        ),
        openai_api_key=os.environ.get("OPENAI_API_KEY") or None,
        embedding_model=os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
        embedding_dimensions=int(os.environ.get("OPENAI_EMBEDDING_DIMENSIONS", "1536")),
    )
