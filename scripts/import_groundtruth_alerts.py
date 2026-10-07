#!/usr/bin/env python3
"""把一个 groundtruth CSV 文件批量导入为告警（逐行调后端 POST /api/alerts）。

映射规则（与用户确认过的口径一致）：
- alertName   ← CSV 的「故障内容」列
- severity    ← 按「anomaly_type」列查 SEVERITY_MAP 得到
- service     ← CSV 的「service」列（命名已与告警口径对齐，原样使用）
- startsAt    ← st_time 截到秒（东八区本地时间，项目约定不转时区，原样入库）
- endsAt      ← ed_time 截到秒，同上
- labels      ← anomaly_type、故障类别等原始信息，JSON 入库供页面/排查用
- 三布尔打标  ← isProd=1、isEval=1、isTest=0（既是评测集也计入正式口径）

去重说明：alert 表唯一键是 (service, alertName, starts_at)，同一行重复导入时
后端会返回"该告警已存在"，脚本识别后跳过，因此本脚本可以安全地重复执行。

用法（必须显式指定一个 CSV 文件）：
    python scripts/import_groundtruth_alerts.py <csv 文件路径> [--base-url http://localhost:8080]
"""
import argparse
import csv      # 标准库 CSV 解析，自动处理 "JVM;CPU" 这类引号包裹字段和 UTF-8 BOM
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


# anomaly_type → severity 映射表：
# 内存/OOM/网络故障会直接威胁服务可用性，记 critical；
# CPU、磁盘使用率类属于资源水位告警，记 warning。
# 如果后续 CSV 出现表中没有的 anomaly_type，该行会被跳过并提示，需先补这张表。
SEVERITY_MAP = {
    "MEMORY": "critical",
    "JVM;MEMORY": "critical",
    "NETWORK": "critical",
    "CPU": "warning",
    "JVM;CPU": "warning",
    "DISK": "warning",
}


def trim_time(t: str) -> str:
    """把 "2021-03-04 11:50:00.000000" 截成 "2021-03-04 11:50:00"。

    项目约定时间格式统一 yyyy-MM-dd HH:mm:ss、不考虑时区，所以这里只截断
    微秒部分，不做任何时区换算。
    """
    return t[:19]


def post_alert(base_url: str, body: dict) -> tuple[bool, bool, str]:
    """调用后端创建告警接口，返回 (是否成功, 是否重复, 后端消息)。"""
    # 用标准库 urllib 发 POST，避免脚本引入第三方依赖
    req = urllib.request.Request(
        f"{base_url}/api/alerts",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 后端校验失败返回 400，响应体仍是 ApiResponse 结构，读出来区分"重复"和"真失败"
        data = json.loads(e.read().decode("utf-8"))
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        # 连不上后端、返回体不是 JSON 等情况，归为失败让上层报错
        return False, False, f"请求异常: {e}"

    if data.get("code") == 200:
        return True, False, f"id={data['data']['id']}"
    msg = data.get("message", "")
    # 唯一键冲突属于预期情况（脚本可重复执行），单独标出来不当作失败
    return False, "已存在" in msg, msg


def import_file(base_url: str, path: Path) -> tuple[int, int, int]:
    """导入单个 CSV 文件，返回 (导入数, 重复数, 失败数)。"""
    inserted = duplicated = failed = 0
    print(f"\n== {path.name} ==")
    # utf-8-sig：自动剥掉 Excel 导出的 BOM 头，否则第一列列名会带 \ufeff
    with open(path, encoding="utf-8-sig", newline="") as f:
        # DictReader 按表头取名，CSV 里中英混排列名（如「故障内容」）直接当 key 用
        for row in csv.DictReader(f):
            # 组装告警请求体，字段映射规则见文件头注释
            body = {
                "alertName": row["故障内容"],
                "severity": SEVERITY_MAP.get(row["anomaly_type"]),
                "service": row["service"],
                "startsAt": trim_time(row["st_time"]),
                "endsAt": trim_time(row["ed_time"]),
                "labels": {
                    "anomalyType": row["anomaly_type"],
                    "faultCategory": row["故障类别"],
                    "groundtruthId": row["id"],
                    "dataType": row["data_type"],
                    "source": "groundtruth",
                },
                "isProd": True,
                "isEval": True,
                "isTest": False,
            }
            desc = f'{body["service"]} {body["startsAt"]} {body["alertName"]}'
            # 关键字段缺失（含 anomaly_type 不在映射表里时 severity 为 None）→ 跳过并提示
            if not (body["alertName"] and body["severity"] and body["service"] and body["startsAt"]):
                print(f"  [跳过] 字段缺失或 anomaly_type 无映射: {desc}")
                failed += 1
                continue

            ok, dup, msg = post_alert(base_url, body)
            if ok:
                inserted += 1
                print(f"  [导入] {desc} -> {msg}")
            elif dup:
                duplicated += 1
                print(f"  [重复] {desc}")
            else:
                failed += 1
                print(f"  [失败] {desc}: {msg}")
    return inserted, duplicated, failed


def main() -> int:
    parser = argparse.ArgumentParser(
        description="groundtruth CSV 批量导入告警（一次导入一个文件）")
    parser.add_argument("csv_file", help="要导入的 CSV 文件路径（单文件）")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="后端地址（默认 http://localhost:8080）")
    args = parser.parse_args()

    path = Path(args.csv_file)
    if not path.is_file():
        print(f"文件不存在: {path}", file=sys.stderr)
        return 2

    inserted, duplicated, failed = import_file(args.base_url, path)
    print(f"\n完成：导入 {inserted}，重复跳过 {duplicated}，失败 {failed}")
    # 有失败行时退出码为 1，方便脚本化场景里感知异常
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
