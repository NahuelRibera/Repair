"""Thin wrapper around the OpenAI embeddings endpoint with bounded retries.
Kept separate from the Java OpenAiClient (different runtime, same
provider) — both are intentionally simple, inspectable HTTP/SDK calls
rather than routed through a shared abstraction, per the project's
"prefer direct, inspectable integration" guidance.
"""
from __future__ import annotations

from dataclasses import dataclass

from openai import APIError, APITimeoutError, OpenAI, RateLimitError
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential


@dataclass
class EmbeddingBatchResult:
    vectors: list[list[float]]
    model: str
    total_tokens: int | None
    """Usage reported by the API for this call, when the SDK response
    includes it. None means unmeasured, never a guessed value."""


class EmbeddingError(RuntimeError):
    pass


def embed_batch(client: OpenAI, model: str, texts: list[str]) -> EmbeddingBatchResult:
    # At most 2 retries (3 total attempts) on transient failures only.
    # Authentication, invalid-request/schema, unsupported-model, and
    # exhausted-credit errors are not RateLimitError/APITimeoutError and so
    # are never retried here — they surface immediately as EmbeddingError.
    @retry(
        retry=retry_if_exception_type((RateLimitError, APITimeoutError)),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=20),
        reraise=True,
    )
    def _call():
        return client.embeddings.create(model=model, input=texts)

    try:
        response = _call()
    except (RateLimitError, APITimeoutError, APIError) as e:
        raise EmbeddingError(f"OpenAI embeddings call failed: {e}") from e

    vectors = [item.embedding for item in response.data]
    total_tokens = getattr(response.usage, "total_tokens", None) if response.usage else None
    return EmbeddingBatchResult(vectors=vectors, model=response.model, total_tokens=total_tokens)
