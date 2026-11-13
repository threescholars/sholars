# sholars

Utilities for looking up mobile-app privacy policy URLs.

## Huawei AppGallery privacy URL

Resolves an AppGallery privacy-policy URL from an **exact** app display name via the store's JSON API (no browser). Same logic in Python and Java (JDK stdlib only).

### Requirements

- Python 3.10+ **or** JDK 11+ (no third-party deps)

### Usage (Python)

```bash
python3 huawei_appgallery_privacy.py 快手
python3 huawei_appgallery_privacy.py 快手 --json
```

### Usage (Java)

```bash
javac HuaweiAppGalleryPrivacy.java
java HuaweiAppGalleryPrivacy 快手
java HuaweiAppGalleryPrivacy 快手 --json
```

Optional flags (both): `--zone CN`, `--locale zh_CN`, `--base-url https://web-drcn.hispace.dbankcloud.cn/edge`

### Match rule

Uses the **first** search result only if its `name` exactly equals the query (so `快手` does not accept `快手极速版`).

### Example (verified)

```text
$ java HuaweiAppGalleryPrivacy 快手
https://app.m.kuaishou.com/public/index.html#/protocol/detail?draft=1&id=19&uniqueCode=v3_news_1013106586_1535460891301_3
```
