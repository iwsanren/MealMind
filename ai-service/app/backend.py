"""Async client for the Java backend's internal API (see notes/agent/internal-api.md)."""

from typing import Any

import httpx


class ToolError(Exception):
    """A backend or tool failure that should be reported back to the model as a tool result."""


class BackendClient:
    def __init__(self, base_url: str, timeout_s: float = 10.0, transport: httpx.AsyncBaseTransport | None = None):
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout_s, transport=transport)
        self._slot_options: dict[str, list[str]] | None = None

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = await self._client.request(method, path, **kwargs)
        except httpx.HTTPError as e:
            # Class name only: never echo URLs or payloads from a failed request.
            raise ToolError(f"backend unreachable ({type(e).__name__})") from e
        if response.status_code >= 400:
            # The backend answers 4xx with {"message": ...}; a malformed body is a 500 with the same shape.
            try:
                message = response.json().get("message", "")
            except ValueError:
                message = response.text[:200]
            raise ToolError(f"backend returned HTTP {response.status_code}: {message}")
        return response.json()

    async def search_meals(self, body: dict[str, Any]) -> dict[str, Any]:
        return await self._request("POST", "/internal/v1/meals/search", json=body)

    async def recent_feedback(self, user_id: int, limit: int | None = None) -> dict[str, Any]:
        params = {"limit": limit} if limit is not None else None
        return await self._request("GET", f"/internal/v1/users/{user_id}/recent-feedback", params=params)

    async def risk_check(self, text: str) -> dict[str, Any]:
        return await self._request("POST", "/internal/v1/risk/check", json={"text": text})

    async def write_trace(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._request("POST", "/internal/v1/traces", json=payload)

    async def slot_options(self) -> dict[str, list[str]]:
        """Allowed tag values per dimension (public API); cached for the life of the client."""
        if self._slot_options is None:
            self._slot_options = await self._request("GET", "/api/v1/slot-options")
        return self._slot_options
