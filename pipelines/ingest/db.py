from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

import psycopg

from ingest.config import Config


@contextmanager
def connect(config: Config) -> Iterator[psycopg.Connection]:
    with psycopg.connect(config.dsn, autocommit=False) as conn:
        yield conn
