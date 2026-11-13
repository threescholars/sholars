#!/usr/bin/env python3
"""Resolve a Huawei AppGallery privacy-policy URL from an exact app name.

Uses the public web edge JSON API (no DOM scraping):
  1) POST /webedge/getInterfaceCode  (Identity-Id bound JWT)
  2) GET  /uowap/index?uri=searchApp|<name>
  3) GET  /uowap/index?uri=app|<appid>  → conceal.text

Example:
  python3 huawei_appgallery_privacy.py 快手
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

DEFAULT_BASE = "https://web-drcn.hispace.dbankcloud.cn/edge"
DEFAULT_ZONE = "CN"
DEFAULT_LOCALE = "zh_CN"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/120.0.0.0 Safari/537.36"
)
ORIGIN = "https://appgallery.huawei.com"
REFERER = "https://appgallery.huawei.com/"


class AppGalleryError(RuntimeError):
    """Raised when lookup fails for a documented reason."""


class AppGalleryClient:
    def __init__(
        self,
        base_url: str = DEFAULT_BASE,
        zone: str = DEFAULT_ZONE,
        locale: str = DEFAULT_LOCALE,
        timeout: float = 45.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.zone = zone
        self.locale = locale
        self.timeout = timeout
        self.identity_id = str(uuid.uuid4())
        self._interface_code: str | None = None

    def _request(
        self,
        method: str,
        url: str,
        *,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        req = urllib.request.Request(
            url,
            data=data,
            headers=headers or {},
            method=method,
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()

    def refresh_interface_code(self) -> str:
        status, body = self._request(
            "POST",
            f"{self.base_url}/webedge/getInterfaceCode",
            data=b"",
            headers={
                "User-Agent": UA,
                "Accept": "application/json, text/plain, */*",
                "Origin": ORIGIN,
                "Referer": REFERER,
                "Identity-Id": self.identity_id,
            },
        )
        if status != 200:
            raise AppGalleryError(
                f"getInterfaceCode failed HTTP {status}: {body[:200]!r}"
            )
        code = json.loads(body.decode("utf-8"))
        if not isinstance(code, str) or not code:
            raise AppGalleryError("getInterfaceCode returned empty token")
        self._interface_code = code
        return code

    def _auth_headers(self) -> dict[str, str]:
        if not self._interface_code:
            self.refresh_interface_code()
        assert self._interface_code is not None
        return {
            "User-Agent": UA,
            "Accept": "application/json, text/plain, */*",
            "Origin": ORIGIN,
            "Referer": REFERER,
            "Identity-Id": self.identity_id,
            "Interface-Code": f"{self._interface_code}_{int(time.time() * 1000)}",
        }

    def uowap(self, params: dict[str, str]) -> dict[str, Any]:
        qs = urllib.parse.urlencode(params)
        url = f"{self.base_url}/uowap/index?{qs}"
        status, body = self._request("GET", url, headers=self._auth_headers())
        if status == 403 and b"InterfaceCode" in body:
            self.refresh_interface_code()
            status, body = self._request("GET", url, headers=self._auth_headers())
        if status != 200:
            raise AppGalleryError(f"uowap HTTP {status}: {body[:300]!r}")
        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise AppGalleryError(f"uowap non-JSON response: {body[:200]!r}") from exc
        if isinstance(payload, dict) and payload.get("rtnCode") not in (0, None, "0"):
            raise AppGalleryError(
                f"uowap rtnCode={payload.get('rtnCode')} "
                f"rtnDesc={payload.get('rtnDesc')!r}"
            )
        return payload

    def search_apps(self, app_name: str) -> list[dict[str, Any]]:
        payload = self.uowap(
            {
                "method": "internal.getTabDetail",
                "serviceType": "20",
                "reqPageNum": "1",
                "maxResults": "25",
                "uri": f"searchApp|{app_name}",
                "zone": self.zone,
                "locale": self.locale,
            }
        )
        apps: list[dict[str, Any]] = []
        for block in payload.get("layoutData") or []:
            for item in block.get("dataList") or []:
                if isinstance(item, dict) and item.get("appid") and item.get("name"):
                    apps.append(item)
        return apps

    def app_detail(self, app_id: str) -> dict[str, Any]:
        return self.uowap(
            {
                "method": "internal.getTabDetail",
                "serviceType": "20",
                "reqPageNum": "1",
                "maxResults": "25",
                "uri": f"app|{app_id}",
                "appid": app_id,
                "zone": self.zone,
                "locale": self.locale,
            }
        )


def first_exact_match(apps: list[dict[str, Any]], app_name: str) -> dict[str, Any]:
    """User rule: use the first search hit only when its name equals the query."""
    if not apps:
        raise AppGalleryError(f"no search results for {app_name!r}")
    first = apps[0]
    if first.get("name") != app_name:
        raise AppGalleryError(
            "first result name does not exactly match query: "
            f"query={app_name!r} first={first.get('name')!r} "
            f"appid={first.get('appid')!r}"
        )
    return first


def extract_privacy_url(detail: dict[str, Any]) -> str:
    """Find conceal.text (隐私政策) anywhere in layoutData."""
    for block in detail.get("layoutData") or []:
        for item in block.get("dataList") or []:
            if not isinstance(item, dict):
                continue
            conceal = item.get("conceal")
            if isinstance(conceal, dict):
                text = conceal.get("text")
                if isinstance(text, str) and text.strip():
                    return text.strip()
    raise AppGalleryError("privacy policy URL missing (no conceal.text in detail)")


def lookup_privacy_url(
    app_name: str,
    *,
    base_url: str = DEFAULT_BASE,
    zone: str = DEFAULT_ZONE,
    locale: str = DEFAULT_LOCALE,
) -> dict[str, str]:
    client = AppGalleryClient(base_url=base_url, zone=zone, locale=locale)
    apps = client.search_apps(app_name)
    match = first_exact_match(apps, app_name)
    app_id = str(match["appid"])
    detail = client.app_detail(app_id)
    privacy_url = extract_privacy_url(detail)
    return {
        "app_name": str(match["name"]),
        "app_id": app_id,
        "package": str(match.get("package") or ""),
        "detail_url": f"https://appgallery.huawei.com/app/{app_id}",
        "privacy_url": privacy_url,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fetch Huawei AppGallery privacy-policy URL by exact app name."
    )
    parser.add_argument("app_name", help="Exact displayed app name, e.g. 快手")
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE,
        help=f"Edge API base (default: {DEFAULT_BASE})",
    )
    parser.add_argument(
        "--zone",
        default=DEFAULT_ZONE,
        help="Store zone for China catalog (default: CN)",
    )
    parser.add_argument(
        "--locale",
        default=DEFAULT_LOCALE,
        help="Locale (default: zh_CN)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print full JSON result instead of privacy URL only",
    )
    args = parser.parse_args(argv)

    try:
        result = lookup_privacy_url(
            args.app_name,
            base_url=args.base_url,
            zone=args.zone,
            locale=args.locale,
        )
    except AppGalleryError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001 - CLI boundary
        print(f"error: unexpected failure: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(result["privacy_url"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
