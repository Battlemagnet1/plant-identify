#!/usr/bin/env python3
"""
OpenAI Compatible 模拟服务端 —— 用于在**没有真实 API Key** 的情况下
验证植物识别 App 的完整链路。

## 为什么需要它

识别链路里真正容易出错的不是「能不能连通」，而是：
  - 模型返回 ```json 围栏 / 前后带解释文字时能否正确剥离
  - 字段名写成 camelCase 时能否识别
  - 字段缺失时能否降级而不是返回空白
  - 完全不是 JSON 时能否保留原文
  - 401 / 404 / 400 / 429 / 5xx 能否给出各自对应的中文提示
  - 是否真的把 N 张图和部位标注发了出来

这些用真实服务很难稳定复现（也不该为了测一个错误分支去烧额度）。
本脚本把这些情形都做成可切换的模式，让验证变成确定性的。

## 用法

    python tools/mock_ai_server.py --port 8899

App 侧配置：
    Base URL   http://127.0.0.1:8899/v1
    API Key    任意非空字符串
    模型名     任意非空字符串

配合 adb 的反向端口转发（设备上的 127.0.0.1:8899 会转到本机）：

    adb reverse tcp:8899 tcp:8899

## 切换响应模式

    curl -X POST "http://127.0.0.1:8899/control?mode=ok"

可选 mode：
    ok            完整结构化 JSON（默认）
    fenced        带 ```json 代码块围栏
    chatty        JSON 前后夹带解释文字
    camel         字段名用 camelCase（latinName 等）
    partial       只返回 name 与 confidence
    notjson       返回纯文字描述，完全不是 JSON
    empty         content 为空字符串
    slow          延迟 3 秒再返回
    401 / 404 / 400model / 400image / 429 / 500   对应 HTTP 错误

## 查看收到的请求

    curl http://127.0.0.1:8899/log      # 最后一次请求的摘要
    curl -X POST http://127.0.0.1:8899/reset
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# ---------------------------------------------------------------- 响应模板

FULL_RESULT = {
    "name": "紫薇",
    "latin_name": "Lagerstroemia indica",
    "family": "千屈菜科",
    "genus": "紫薇属",
    "category": "落叶灌木或小乔木",
    "confidence": 0.91,
    "evidence": ["叶片形态", "整体株型", "花部特征"],
    "missing_information": [],
    "possible_alternatives": [{"name": "大花紫薇", "confidence": 0.06}],
    "conflicts": [],
}

CAMEL_RESULT = {
    "name": "紫薇",
    "latinName": "Lagerstroemia indica",
    "family": "千屈菜科",
    "genus": "紫薇属",
    "category": "落叶灌木或小乔木",
    "confidence": "91%",  # 顺带测试百分比字符串
    "evidence": "叶片形态, 整体株型, 花部特征",  # 顺带测试「数组写成字符串」
    "missingInformation": [],
    "possibleAlternatives": ["大花紫薇"],  # 顺带测试「对象数组写成字符串数组」
    "conflicts": [],
}

PARTIAL_RESULT = {
    "name": "紫薇（疑似）",
    "confidence": 0.62,
}

PLAIN_TEXT = (
    "从这几张照片来看，我判断这是一株紫薇。\n\n"
    "主要依据是它的叶片呈椭圆形，对生；树皮平滑，呈灰色；整体株型是灌木状。\n"
    "秋季叶片会转红，也是很典型的特征。\n\n"
    "不过照片里没有拍清楚花，所以不能完全确定是不是大花紫薇。"
)


def build_content(mode: str) -> str:
    """按模式生成 content 字段。"""
    if mode == "ok":
        return json.dumps(FULL_RESULT, ensure_ascii=False)

    if mode == "fenced":
        return "```json\n" + json.dumps(FULL_RESULT, ensure_ascii=False) + "\n```"

    if mode == "chatty":
        return (
            "我已经仔细比对了你提供的这几张照片，判断如下：\n\n"
            + json.dumps(FULL_RESULT, ensure_ascii=False)
            + "\n\n如果还需要补充其他部位的判断，可以再拍几张照片给我。"
        )

    if mode == "camel":
        return json.dumps(CAMEL_RESULT, ensure_ascii=False)

    if mode == "partial":
        return json.dumps(PARTIAL_RESULT, ensure_ascii=False)

    if mode == "notjson":
        return PLAIN_TEXT

    if mode == "empty":
        return ""

    return json.dumps(FULL_RESULT, ensure_ascii=False)


# ---------------------------------------------------------------- 状态

STATE = {"mode": "ok"}
LAST_REQUEST: dict = {}
LOCK = threading.Lock()


# ---------------------------------------------------------------- Handler


class MockHandler(BaseHTTPRequestHandler):
    server_version = "PlantIdentifyMock/1.0"

    # 关掉默认的每请求日志，避免刷屏；重要信息由我们自己打印
    def log_message(self, fmt, *args):  # noqa: D102
        pass

    # ------------------------------------------------ GET
    def do_GET(self):  # noqa: N802
        path = urlparse(self.path).path

        if path == "/log":
            self._send_json(200, LAST_REQUEST or {"empty": True})
            return

        if path == "/health":
            self._send_json(200, {"ok": True, "mode": STATE["mode"]})
            return

        self._send_json(404, {"error": {"message": f"unknown path {path}"}})

    # ------------------------------------------------ POST
    def do_POST(self):  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path

        # ---- 控制端点
        if path == "/control":
            query = parse_qs(parsed.query)
            mode = (query.get("mode") or ["ok"])[0]
            with LOCK:
                STATE["mode"] = mode
            print(f"[mock] mode -> {mode}", flush=True)
            self._send_json(200, {"ok": True, "mode": mode})
            return

        if path == "/reset":
            with LOCK:
                LAST_REQUEST.clear()
            print("[mock] log cleared", flush=True)
            self._send_json(200, {"ok": True})
            return

        # ---- 兼容端点：接受 /v1/chat/completions 与 /chat/completions
        if path not in ("/v1/chat/completions", "/chat/completions"):
            self._send_json(404, {"error": {"message": f"unknown path {path}"}})
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", errors="replace")

        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            self._send_json(400, {"error": {"message": "invalid JSON in request body"}})
            return

        # 记录请求摘要 —— 这是验证「到底发了几张图、prompt 写了什么」的关键
        content_parts = []
        try:
            messages = body.get("messages") or []
            first = messages[0] if messages else {}
            content = first.get("content")
            if isinstance(content, list):
                content_parts = content
            elif isinstance(content, str):
                content_parts = [{"type": "text", "text": content}]
        except (AttributeError, IndexError, TypeError):
            pass

        images = [p for p in content_parts if p.get("type") == "image_url"]
        texts = [p for p in content_parts if p.get("type") == "text"]

        with LOCK:
            LAST_REQUEST.clear()
            LAST_REQUEST.update(
                {
                    "model": body.get("model"),
                    "image_count": len(images),
                    "image_bytes": sum(len(p.get("image_url", {}).get("url", "")) for p in images),
                    "text": texts[0].get("text", "") if texts else "",
                    "has_response_format": "response_format" in body,
                    "has_temperature": "temperature" in body,
                    "authorization_present": bool(self.headers.get("Authorization")),
                }
            )

        mode = STATE["mode"]
        print(
            f"[mock] POST {path} mode={mode} images={len(images)} "
            f"model={body.get('model')}",
            flush=True,
        )

        # ---- 延迟模式
        if mode == "slow":
            time.sleep(3)

        # ---- HTTP 错误模式
        if mode == "401":
            self._send_json(
                401,
                {"error": {"message": "Incorrect API key provided: sk-abc***xyz. Invalid API key."}},
            )
            return

        if mode == "404":
            self._send_json(404, {"error": {"message": "Not Found: /v2/chat/completions"}})
            return

        if mode == "400model":
            self._send_json(
                400,
                {"error": {"message": "The model `no-such-model-v9` does not exist"}},
            )
            return

        if mode == "400image":
            self._send_json(
                400,
                {
                    "error": {
                        "message": "Invalid content type: image_url is not supported "
                        "by this text only model"
                    }
                },
            )
            return

        if mode == "429":
            self._send_json(
                429,
                {"error": {"message": "Rate limit reached. Please retry after 20s."}},
            )
            return

        if mode == "500":
            self._send_json(500, {"error": {"message": "Internal server error"}})
            return

        # ---- 成功响应
        payload = {
            "id": "chatcmpl-mock-0001",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": body.get("model") or "mock-model",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": build_content(mode)},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 1024, "completion_tokens": 128, "total_tokens": 1152},
        }
        self._send_json(200, payload)

    # ------------------------------------------------ 工具
    def _send_json(self, code: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main() -> None:
    parser = argparse.ArgumentParser(description="OpenAI 兼容的模拟服务端")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址")
    parser.add_argument("--port", type=int, default=8899, help="监听端口")
    parser.add_argument("--mode", default="ok", help="启动时的响应模式")
    args = parser.parse_args()

    STATE["mode"] = args.mode

    server = ThreadingHTTPServer((args.host, args.port), MockHandler)
    print(f"[mock] listening on http://{args.host}:{args.port}", flush=True)
    print(f"[mock] mode = {STATE['mode']}", flush=True)
    print("[mock] 切换模式: curl -X POST 'http://127.0.0.1:%d/control?mode=ok'" % args.port, flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] shutting down", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
