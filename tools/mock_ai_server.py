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
    ---- 视觉识别（请求带图片）----
    ok            完整结构化 JSON（默认）
    fenced        带 ```json 代码块围栏
    chatty        JSON 前后夹带解释文字
    camel         字段名用 camelCase（latinName 等）
    partial       只返回 name 与 confidence
    lowconf       字段完整但 confidence=0.62（验证补图提示）
    notjson       返回纯文字描述，完全不是 JSON
    empty         content 为空字符串
    slow          延迟 3 秒再返回
    401 / 404 / 400model / 400image / 429 / 500   对应 HTTP 错误
    params        仅带图请求报 400 InvalidParameter，纯文本请求正常
                  （复刻阿里云百炼对过小图片的行为，用于验证测试连接的降级逻辑）

    ---- 文字分析（请求不带图片，与上面共用同一个 mode 开关）----
    analysisEmpty    七个字段全为空字符串
    analysisBad      返回一句话，不是 JSON
    analysisPartial  只返回 description 与 flowering_period
    analysisFenced   带代码块围栏
    analysisSnake    键名用中文（简介/形态特征/…），检验键名归一化

    默认（未指定上述文字模式时）返回一份完整的植物百科 JSON。
    之所以不复用视觉那套模式：`ok` 对文字分析意味着「返回完整百科」，
    而 `notjson` 这类模式对两条通道的语义不同，混在一起会很难读。

## 查看收到的请求

    curl http://127.0.0.1:8899/log      # 最后一次请求的摘要
    curl -X POST http://127.0.0.1:8899/reset
"""

from __future__ import annotations

import argparse
import json
import re
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

# 低置信度场景：规格书第六节要求 confidence < 0.70 时提示补充照片。
# 与 partial 的区别是它**字段完整**，只是把握不大 —— 用来验证
# 「结构没问题的低置信度」也能触发补图提示。
LOW_CONFIDENCE_RESULT = {
    "name": "紫薇（疑似）",
    "latin_name": "Lagerstroemia indica",
    "family": "千屈菜科",
    "genus": "紫薇属",
    "category": "落叶灌木或小乔木",
    "confidence": 0.62,
    "evidence": ["叶片形态", "整体株型"],
    "missing_information": ["花部特征", "完整株型"],
    "possible_alternatives": [{"name": "大花紫薇", "confidence": 0.25}],
    "conflicts": [],
}

PLAIN_TEXT = (
    "从这几张照片来看，我判断这是一株紫薇。\n\n"
    "主要依据是它的叶片呈椭圆形，对生；树皮平滑，呈灰色；整体株型是灌木状。\n"
    "秋季叶片会转红，也是很典型的特征。\n\n"
    "不过照片里没有拍清楚花，所以不能完全确定是不是大花紫薇。"
)

# ---------------------------------------------------------------- 文字分析

# 植物百科的响应。它对应**不带图片**的请求 ——
# 服务端据此区分「这是视觉识别还是文字分析」，一个模式同时服务两条通道。
ANALYSIS_RESULT = {
    "common_names": "紫薇花、痒痒树、满堂红",
    "description": "紫薇是千屈菜科紫薇属的落叶灌木或小乔木，夏季开花，花期可长达数月。",
    "morphological_features": "树皮平滑呈灰色，枝干常扭曲；叶互生或对生，椭圆形至倒卵形；"
    "圆锥花序顶生，花瓣皱缩。",
    "growth_habits": "喜光，稍耐半阴，喜温暖湿润气候；耐旱怕涝，对土壤要求不严。",
    "flowering_period": "6—9 月",
    "fruiting_period": "9—12 月",
    "landscape_uses": "园林中常用作行道树、庭荫树与花篱，也可盆栽观赏。",
    "care_advice": "生长期保持土壤湿润但不积水；花后适度修剪可促发新枝、延长花期。",
    "pest_control": "蚜虫：发生时喷亚醋虫消，注意叶背；白粉病：加强通风、避免叶片长期潮湿，发病初期剪除病叶。",
}

# 一句话带过，模拟「模型没按要求输出 JSON」
ANALYSIS_PLAIN_TEXT = "抱歉，我无法生成这株植物的完整百科介绍。"

# JSON 合法但所有字段为空
ANALYSIS_EMPTY = {
    "common_names": "",
    "description": "",
    "morphological_features": "",
    "growth_habits": "",
    "flowering_period": "",
    "fruiting_period": "",
    "landscape_uses": "",
    "care_advice": "",
    "pest_control": "",
}


def build_analysis_content(mode: str) -> str:
    """按模式生成文字分析的 content 字段。"""
    if mode == "analysisEmpty":
        return json.dumps(ANALYSIS_EMPTY, ensure_ascii=False)

    if mode == "analysisBad":
        return ANALYSIS_PLAIN_TEXT

    if mode == "analysisPartial":
        return json.dumps(
            {
                "description": "紫薇是千屈菜科紫薇属的落叶灌木或小乔木。",
                "flowering_period": "6—9 月",
            },
            ensure_ascii=False,
        )

    if mode == "analysisFenced":
        return "```json\n" + json.dumps(ANALYSIS_RESULT, ensure_ascii=False) + "\n```"

    if mode == "analysisSnake":
        # 键名写成中文，检验解析器的归一化匹配
        return json.dumps(
            {
                "俗称": ANALYSIS_RESULT["common_names"],
                "简介": ANALYSIS_RESULT["description"],
                "形态特征": ANALYSIS_RESULT["morphological_features"],
                "生长习性": ANALYSIS_RESULT["growth_habits"],
                "花期": ANALYSIS_RESULT["flowering_period"],
                "果期": ANALYSIS_RESULT["fruiting_period"],
                "园林用途": ANALYSIS_RESULT["landscape_uses"],
                "养护建议": ANALYSIS_RESULT["care_advice"],
                "病虫害防治": ANALYSIS_RESULT["pest_control"],
            },
            ensure_ascii=False,
        )

    return json.dumps(ANALYSIS_RESULT, ensure_ascii=False)


# 「数据清洗顾问」的 prompt 里每组以「### 候选 <id>」开头。
# 把 id 抽出来，mock 才能**按组回答** —— 而不是不管问什么都回同一段，
# 那样连「group_id 对得上」这件事都验证不了。
CLEANING_GROUP_RE = re.compile(r"###\s*候选\s*(\d+)")

CLEANING_MARKER = "待判定候选"


def build_cleaning_content(mode: str, prompt: str) -> str:
    """按清洗顾问 prompt 里的候选组生成结论。

    四种模式覆盖了编排层需要区分的全部路径：
      cleaningSame    全部判为同一株（→ 界面出现待合并）
      cleaningNotSame 全部判为不同种（→ 自动结案，列表应清空）
      cleaningAlias   全部判为「名称差异」（→ 自动结案，理由不同）
      cleaningBad     返回非 JSON（→ 该批留待下次，aiUsed 必须仍为 0）
    """
    ids = CLEANING_GROUP_RE.findall(prompt)

    if mode == "cleaningBad":
        return "这些植物看起来可能是同一种，建议合并。"

    results = []
    for gid in ids:
        if mode == "cleaningNotSame":
            same, vtype, reason = False, "NOT_SAME", "拉丁学名与科属均不同"
        elif mode == "cleaningAlias":
            same, vtype, reason = False, "ALIAS_RELATION", "同一物种的常见异名写法"
        else:
            same, vtype, reason = True, "POSSIBLE_DUPLICATE", "拉丁学名一致，属于同一物种"
        results.append(
            {
                "group_id": gid,
                "type": vtype,
                "is_same": same,
                "confidence": 0.95 if same else 0.88,
                "reason": reason,
            }
        )

    return json.dumps({"results": results}, ensure_ascii=False)


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

    if mode == "lowconf":
        return json.dumps(LOW_CONFIDENCE_RESULT, ensure_ascii=False)

    if mode == "notjson":
        return PLAIN_TEXT

    if mode == "empty":
        return ""

    return json.dumps(FULL_RESULT, ensure_ascii=False)


# ---------------------------------------------------------------- 状态

# 本文件支持的全部模式。
#
# 除了给 /control 做校验（打错字立刻报错，而不是静默退化成默认响应），
# 它还解决一个更隐蔽的问题：**验证脚本无法确认服务端进程是不是最新代码**。
# Python 在启动时就把源码载入内存，之后编辑文件对已运行的进程没有任何影响。
# 一旦忘了重启，就会出现「模式切了、响应没变」的假象，而 /health 报的
# mode 又完全正确 —— 这条弯路真实发生过。现在 /health 会一并返回本清单，
# 脚本只要断言目标模式在清单里，就能立刻发现自己在跟旧进程说话。
KNOWN_MODES = (
    # 视觉识别
    "ok", "fenced", "chatty", "camel", "partial", "lowconf",
    "notjson", "empty", "slow",
    # HTTP 错误
    "401", "404", "400model", "400image", "400params", "429", "500", "params",
    # 文字分析
    "analysisEmpty", "analysisBad", "analysisPartial", "analysisFenced",
    "analysisSnake",
    # 数据清洗顾问
    "cleaningSame", "cleaningNotSame", "cleaningAlias", "cleaningBad",
)

STATE = {"mode": "ok"}
LAST_REQUEST: dict = {}
ALL_REQUESTS: list = []
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

        # 全部请求（用于验证「先带图失败、再纯文本重试」这类多步行为）
        if path == "/requests":
            with LOCK:
                self._send_json(200, {"count": len(ALL_REQUESTS), "requests": list(ALL_REQUESTS)})
            return

        if path == "/health":
            # modes 一并返回：验证脚本据此确认「磁盘上的脚本」与
            # 「正在跑的进程」是同一份代码。
            #
            # 这个字段是被真实事故逼出来的 —— Python 进程在启动时就把源码
            # 载入内存，之后编辑文件对已运行的进程毫无影响。于是出现过
            # 这种假象：/health 报着 mode=lowconf，实际返回的却是默认结果，
            # 因为跑着的进程里根本没有 lowconf 这个分支。
            # 光看 mode 无法发现，必须能问出「你到底支持哪些模式」。
            self._send_json(
                200,
                {"ok": True, "mode": STATE["mode"], "modes": list(KNOWN_MODES)},
            )
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
            # 不明模式直接拒绝，不静默退化成默认响应 ——
            # 否则「模式名打错」和「模式没生效」这两种情况都无法区分
            if mode not in KNOWN_MODES:
                print(f"[mock] 拒绝未知模式: {mode}", flush=True)
                self._send_json(
                    400,
                    {
                        "error": {
                            "message": f"未知模式 {mode!r}",
                            "known_modes": list(KNOWN_MODES),
                        }
                    },
                )
                return
            with LOCK:
                STATE["mode"] = mode
            print(f"[mock] mode -> {mode}", flush=True)
            self._send_json(200, {"ok": True, "mode": mode})
            return

        if path == "/reset":
            with LOCK:
                LAST_REQUEST.clear()
                ALL_REQUESTS.clear()
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

        record = {
            "model": body.get("model"),
            "image_count": len(images),
            "image_bytes": sum(len(p.get("image_url", {}).get("url", "")) for p in images),
            "text": texts[0].get("text", "") if texts else "",
            "has_response_format": "response_format" in body,
            "has_temperature": "temperature" in body,
            "authorization_present": bool(self.headers.get("Authorization")),
        }

        with LOCK:
            LAST_REQUEST.clear()
            LAST_REQUEST.update(record)
            ALL_REQUESTS.append(dict(record))

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

        # ---- 只对「带图请求」报参数错误，纯文本请求正常
        #
        # 复刻阿里云百炼的真实行为：它对过小的图片会返回
        # `<400> InternalError.Algo.InvalidParameter`，而同样的模型
        # 收纯文本请求完全正常。用于验证「测试连接」的降级逻辑 ——
        # 带图被拒时应自动改用纯文本重试，并报告「连接正常但图片未验证」，
        # 而不是笼统地报「连接失败」。
        if mode == "400params" and len(images) > 0:
            self._send_json(
                400,
                {
                    "error": {
                        "code": "InternalError.Algo.InvalidParameter",
                        "message": "<400> InternalError.Algo.InvalidParameter",
                    }
                },
            )
            return

        # ---- 成功响应
        # 不带图片的请求视为文字分析，带图的视为视觉识别。
        # 这样一个模式可以同时服务两条通道，不必在两套模式间来回切换。
        #
        # 三条纯文本用途靠 prompt 内容区分：清洗顾问 / 百科分析。
        # 用「prompt 里有没有这句话」来判，比再加一个『当前模式』开关稳 ——
        # 开关会与实际请求脱节（切了开关但客户端没变），
        # 而 prompt 是随请求一起到的，不可能对不上。
        user_text = chr(10).join(t.get("text") or "" for t in texts)
        if len(images) == 0 and CLEANING_MARKER in user_text:
            content_text = build_cleaning_content(mode, user_text)
        else:
            content_text = (
                build_analysis_content(mode) if len(images) == 0 else build_content(mode)
            )

        payload = {
            "id": "chatcmpl-mock-0001",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": body.get("model") or "mock-model",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content_text},
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
    # 启动时把「本进程支持哪些模式」打进日志 —— 修改本文件后若忘记重启，
    # 这行输出会立刻暴露新旧差异（进程里的清单不会跟着文件更新）
    print(f"[mock] 支持 {len(KNOWN_MODES)} 种模式: {', '.join(KNOWN_MODES)}", flush=True)
    print("[mock] 切换模式: curl -X POST 'http://127.0.0.1:%d/control?mode=ok'" % args.port, flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] shutting down", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
