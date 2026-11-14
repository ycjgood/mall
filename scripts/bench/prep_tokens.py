#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压测前置：为每个待下单的购物车条目预生成 JWT 与一次性下单令牌。

为什么要预生成：
    登录接口用 BCrypt 校验，单次约 100ms CPU，如果在压测循环里登录，
    会把 CPU 打满、TPS 失真。这里串行做掉"登录 + 领令牌"，压测阶段只打
    /order/generateOrder 一个接口，测出来的才是下单本身的容量。

输入 players.tsv：member_id \t address_id \t cart_id \t username
输出 bench.csv：jwt,order_token,address_id,cart_id
"""
import csv
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8085"
PASSWORD = "123456"


def call(path, data=None, token=None, method=None):
    url = BASE + path
    body = None
    headers = {"Accept": "application/json"}
    if data is not None:
        body = urllib.parse.urlencode(data).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers, method=method or ("POST" if body else "GET"))
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main(src, dst):
    jwt_cache = {}
    rows = []
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            member_id, address_id, cart_id, username = line.split("\t")
            if username not in jwt_cache:
                result = call("/sso/login", {"username": username, "password": PASSWORD})
                if result.get("code") != 200:
                    print(f"登录失败 {username}: {result.get('message')}", file=sys.stderr)
                    continue
                jwt_cache[username] = result["data"]["token"]
            jwt = jwt_cache[username]
            token_result = call("/order/token", token=jwt, method="GET")
            if token_result.get("code") != 200:
                print(f"领令牌失败 {username}: {token_result.get('message')}", file=sys.stderr)
                continue
            rows.append([jwt, token_result["data"], address_id, cart_id])

    with open(dst, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["jwt", "order_token", "address_id", "cart_id"])
        writer.writerows(rows)
    print(f"generated {len(rows)} rows -> {dst} ({len(jwt_cache)} members)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "players.tsv",
         sys.argv[2] if len(sys.argv) > 2 else "bench.csv")
