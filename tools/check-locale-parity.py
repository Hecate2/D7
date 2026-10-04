#!/usr/bin/env python3
"""检查各语言 strings.xml 与中文基准的一致性。

三件事必须严丝合缝，否则运行时才炸：
  1. 键集完全相同（缺一条 = 该语言回退到中文，界面上两种语言混排）；
  2. 每条的格式占位符集合相同（`%1$s` 少一个就是 IllegalFormatException）；
  3. 换行数相同（对话框与多行提示按行排版，少一行就得改布局）。

translatable="false" 的条目（语言名的 endonym）不参与比较，各语言不重复提供。
"""
import re
import sys
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
TAG = re.compile(r"<string name=\"([^\"]+)\"(?P<attrs>[^>]*)>(?P<body>.*?)</string>", re.S)
# 完整地认一个带位置参数与标志宽度的占位符：%1$d、%2$02d、%1$dh。
# 早先用 `%\d+\$[sd]` 认不全 `%2$02d`，会漏报，得按转换符收尾。
PLACEHOLDER = re.compile(r"%(\d+)\$[#0\- +,(]*\d*(?:\.\d+)?([a-zA-Z])")


def slots(body):
    """只取「位置参数 + 转换符」，忽略 0 补零等标志。

    补零与否是各语言自己的排版选择：中文要「05 分」，日语写「5分」就对，
    两者都用 `%2$...d` 是同一件事，不该当成不一致。位置与类型不一致才是真错
    （比如把 `%1$d` 写成没有位置号的 `%d`，重排参数时就会串位）。
    """
    return sorted(PLACEHOLDER.findall(body))


def parse(path):
    out = {}
    for m in TAG.finditer(path.read_text(encoding="utf-8")):
        if 'translatable="false"' in m.group("attrs"):
            continue
        body = m.group("body")
        out[m.group(1)] = (slots(body), body.count("\\n"))
    return out


def main():
    base = parse(RES / "values/strings.xml")
    locales = sorted(p for p in RES.glob("values-*/strings.xml"))
    if not locales:
        print("找不到 values-*/strings.xml")
        return 1
    failures = 0
    for path in locales:
        name = path.parent.name
        other = parse(path)
        missing = sorted(set(base) - set(other))
        extra = sorted(set(other) - set(base))
        for key in missing:
            print(f"[{name}] 缺少 {key}")
            failures += 1
        for key in extra:
            print(f"[{name}] 多出 {key}")
            failures += 1
        for key in sorted(set(base) & set(other)):
            bp, bn = base[key]
            op, on = other[key]
            if bp != op:
                print(f"[{name}] {key} 占位符 {op} != 基准 {bp}")
                failures += 1
            if bn != on:
                print(f"[{name}] {key} 换行数 {on} != 基准 {bn}")
                failures += 1
    print(f"基准 {len(base)} 条；检查了 {len(locales)} 个语言："
          + ("全部一致" if failures == 0 else f"{failures} 处不一致"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())