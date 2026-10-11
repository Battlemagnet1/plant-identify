#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mock_plantnet_server.py —— 本地假 Pl@ntNet v2 服务端，供离线端到端验证。

## 为什么需要它

真机验证时发现：这台模拟器**上传方向被限速到 ~5 KB/s**（对 postman-echo
等任意主机都一样，下载却有 73 KB/s）。带真实照片的识别请求因此必然超时，
**无法用它验证「应用的请求与解析是否正确」**。

所以起一个本地服务端：设备通过 `adb reverse` 访问它，走的是本地回环，
不受外网上传限速影响。它就按官方文档的形状回 Pl@ntNet 的响应。

⚠️ **它不能替代真实 Pl@ntNet 验证** —— 它证明的是「应用的请求构造与
响应解析正确、且识别链路在通道切换后能跑通」，不是「Key 有效」。
Key 的有效性已用 curl 对真实服务端单独验过（不带图返回 400 = 路由通）。

## 用法

    python tools/mock_plantnet_server.py --port 8901
    adb reverse tcp:8901 tcp:8901
    # 然后在应用设置里把 Pl@ntNet 的 Base URL 改成 http://127.0.0.1:8901/v2

## 响应形状

照 https://my.plantnet.org/doc/api/identify 的示例：
`results[].score` / `results[].species.scientificNameWithoutAuthor` /
`genus` / `family` / `commonNames`，另有 `bestMatch`、
`remainingIdentificationRequests`。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 一份真实形状的响应（物种取自官方文档示例）
SAMPLE = {
    "query": {"project": "all", "images": ["abc"], "organs": ["leaf"],
              "includeRelatedImages": False, "noReject": False, "type": None},
    "predictedOrgans": [{"image": "abc", "filename": "image.jpg",
                         "organ": "leaf", "score": 0.91}],
    "language": "en",
    "preferedReferential": "k-world-flora",
    "bestMatch": "Rosa chinensis Jacq.",
    "results": [
        {
            "score": 0.91234,
            "species": {
                "scientificNameWithoutAuthor": "Rosa chinensis",
                "scientificNameAuthorship": "Jacq.",
                "genus": {"scientificNameWithoutAuthor": "Rosa",
                          "scientificNameAuthorship": "", "scientificName": "Rosa"},
                "family": {"scientificNameWithoutAuthor": "Rosaceae",
                           "scientificNameAuthorship": "", "scientificName": "Rosaceae"},
                "commonNames": ["月季", "Chinese rose", "China rose"],
                "scientificName": "Rosa chinensis Jacq.",
            },
            "gbif": {"id": "3002148"},
            "powo": {"id": "739123-1"},
        },
        {
            "score": 0.05933,
            "species": {
                "scientificNameWithoutAuthor": "Rosa gallica",
                "scientificNameAuthorship": "L.",
                "genus": {"scientificNameWithoutAuthor": "Rosa",
                          "scientificNameAuthorship": "", "scientificName": "Rosa"},
                "family": {"scientificNameWithoutAuthor": "Rosaceae",
                           "scientificNameAuthorship": "", "scientificName": "Rosaceae"},
                "commonNames": ["法国蔷薇"],
                "scientificName": "Rosa gallica L.",
            },
        },
    ],
    "version": "mock-2025-01-17 (7.3)",
    "remainingIdentificationRequests": 497,
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # noqa: A003
        sys.stderr.write("[mock-plantnet] " + (fmt % args) + "\n")

    def _json(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        # 不带图 → 400「images is required」，与真实服务端一致，
        # 这样「测试连接」的判据（400 == 通）在本地也成立
        if "/identify/" in self.path:
            self._json(400, {"statusCode": 400, "error": "Bad Request",
                             "message": '"images" is required'})
        else:
            self._json(404, {"statusCode": 404, "error": "Not Found",
                             "message": "unknown path"})

    def do_POST(self):  # noqa: N802
        if "/identify/" not in self.path:
            self._json(404, {"statusCode": 404, "error": "Not Found",
                             "message": f"unknown path {self.path}"})
            return

        if "api-key=" not in self.path:
            self._json(401, {"statusCode": 401, "error": "Unauthorized",
                             "message": "invalid api-key"})
            return

        length = int(self.headers.get("Content-Length") or 0)
        ctype = self.headers.get("Content-Type", "")
        # 把 multipart 读完，避免连接挂住
        remaining = length
        chunks = 0
        while remaining > 0:
            buf = self.rfile.read(min(65536, remaining))
            if not buf:
                break
            remaining -= len(buf)
            chunks += 1

        body_head = ""
        sys.stderr.write(
            f"[mock-plantnet] POST images multipart: {length} bytes, "
            f"ctype={'multipart' if 'multipart' in ctype else ctype}, chunks={chunks}\n"
        )

        # 简单校验：必须真的带了 images 字段
        if b"name=\"images\"" not in ctype.encode() and length < 1000:
            self._json(400, {"statusCode": 400, "error": "Bad Request",
                             "message": '"images" is required'})
            return

        self._json(200, SAMPLE)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8901)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()

    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock-plantnet] listening on http://{args.host}:{args.port} "
          f"(baseUrl 用 http://127.0.0.1:{args.port}/v2)", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
