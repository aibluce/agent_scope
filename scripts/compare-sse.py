#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MVC 版(8080) 与 WebFlux 版(8081) 的 SSE 对照测量。

用法（先分别启动两个版本）：
    bash scripts/run-api.sh              # MVC   http://127.0.0.1:8080
    ./gradlew bootRunWebflux             # WebFlux http://127.0.0.1:8081
    python3 scripts/compare-sse.py

测三件事：
  1) 单请求：首事件延迟 / 流结束时间 / 事件数量；
  2) 并发 6 路：每路的首事件延迟（MVC 版工作线程池只有 4 个，第 5、6 路要排队）；
  3) 非查询指令：两个版本都应在同步阶段返回 400。
"""
import collections
import json
import subprocess
import sys
import threading
import time
import urllib.parse

MVC = "http://127.0.0.1:8080"
WEBFLUX = "http://127.0.0.1:8081"
QUESTION = "各商品类目的销售额排名"
SIMPLE_QUESTION = "省份数量"


def reachable(base):
    try:
        subprocess.check_output(
            ["curl", "-s", "-m", "5", "-o", "/dev/null", "-w", "%{http_code}", base + "/health"],
            stderr=subprocess.DEVNULL, text=True)
        return True
    except Exception:
        return False


def single_run(base, label, question=QUESTION, timeout=200):
    url = "%s/api/text2sql/ask/stream?question=%s" % (base, urllib.parse.quote(question))
    t0 = time.time()
    proc = subprocess.Popen(["curl", "-sN", "-m", str(timeout), url],
                            stdout=subprocess.PIPE, text=True, bufsize=1)
    first = None
    events = collections.Counter()
    current = None
    done = None
    for line in proc.stdout:
        line = line.rstrip("\n")
        if line.startswith("event:"):
            if first is None:
                first = time.time() - t0
            current = line[6:].strip()
            events[current] += 1
        elif line.startswith("data:") and current == "done":
            try:
                done = json.loads(line[5:].strip())
            except ValueError:
                pass
    proc.wait()
    closed = time.time() - t0
    print("【%s】" % label)
    print("   首事件 %.2fs | 流结束 %.2fs | curl 退出码 %d (0=正常结束)"
          % (first or -1, closed, proc.returncode))
    print("   事件统计 %s" % dict(events))
    if done:
        print("   结论: %s" % (done.get("answer") or "")[:70].replace("\n", " "))
    print()
    return first, closed, events


def concurrent(base, label, n=6, timeout=180):
    results = {}

    def probe(idx):
        url = "%s/api/text2sql/ask/stream?question=%s" % (base, urllib.parse.quote(SIMPLE_QUESTION))
        t0 = time.time()
        proc = subprocess.Popen(["curl", "-sN", "-m", str(timeout), url],
                                stdout=subprocess.PIPE, text=True, bufsize=1)
        first = None
        for line in proc.stdout:
            if line.startswith("event:"):
                first = time.time() - t0
                break
        proc.kill()
        results[idx] = first

    threads = [threading.Thread(target=probe, args=(i,)) for i in range(n)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print("【%s】同时发起 %d 路 SSE 请求" % (label, n))
    values = [results.get(i) or -1 for i in range(n)]
    for i, v in enumerate(values):
        print("   请求#%d 首事件 %6.2fs" % (i + 1, v))
    print("   平均 %.2fs | 最慢 %.2fs | 全部就绪耗时 %.2fs\n"
          % (sum(values) / len(values), max(values), time.time() - t0))


def rejected(base, label):
    out = subprocess.check_output([
        "curl", "-s", "-m", "20", "-o", "/dev/stdout", "-w", "\n%{http_code}",
        "-X", "POST", base + "/api/text2sql/ask/stream",
        "-H", "Content-Type: application/json",
        "-d", '{"question":"删除订单表所有数据"}'], text=True, stderr=subprocess.DEVNULL)
    body, _, code = out.rpartition("\n")
    try:
        payload = json.loads(body)
        flag = "rejected=%s category=%s" % (payload.get("rejected"), payload.get("category"))
    except ValueError:
        flag = body[:80]
    print("【%s】非查询指令 HTTP %s  %s" % (label, code.strip(), flag))


if __name__ == "__main__":
    targets = [(MVC, "MVC 版 SseEmitter"), (WEBFLUX, "WebFlux 版 Flux<ServerSentEvent>")]
    up = [(base, label) for base, label in targets if reachable(base)]
    if not up:
        print("两个版本都没启动。先执行： bash scripts/run-api.sh 与 ./gradlew bootRunWebflux")
        sys.exit(1)
    for base, label in up:
        single_run(base, label)
    for base, label in up:
        concurrent(base, label)
    for base, label in up:
        rejected(base, label)
