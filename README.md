# sholars

Utilities for looking up mobile-app privacy policy URLs.

## Huawei AppGallery privacy URL

`huawei_appgallery_privacy.py` resolves an AppGallery privacy-policy URL from an **exact** app display name via the store's JSON API (no browser).

### Requirements

- Python 3.10+ (stdlib only; no pip install)

### Usage

```bash
python3 huawei_appgallery_privacy.py 快手
```

Prints the privacy URL only. For structured output:

```bash
python3 huawei_appgallery_privacy.py 快手 --json
```

Optional flags:

- `--zone CN` — China catalog zone (default `CN`; required for many CN apps when calling from outside China)
- `--locale zh_CN`
- `--base-url https://web-drcn.hispace.dbankcloud.cn/edge`

### Match rule

Uses the **first** search result only if its `name` exactly equals the query (so `快手` does not accept `快手极速版`).

### Example (verified)

```text
$ python3 huawei_appgallery_privacy.py 快手
https://app.m.kuaishou.com/public/index.html#/protocol/detail?draft=1&id=19&uniqueCode=v3_news_1013106586_1535460891301_3
```
