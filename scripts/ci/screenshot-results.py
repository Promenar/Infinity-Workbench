#!/usr/bin/env python3
"""确保分片内每个截图源类都有通过的测试结果。"""

import argparse
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    classes = args.classes.read_text(encoding="utf-8").splitlines()
    if not classes or len(classes) != len(set(classes)):
        raise ValueError("截图分片类清单为空或重复")
    files = list(args.results.glob("TEST-*.xml"))
    if not files:
        raise ValueError("缺少截图测试报告")
    observed = Counter()
    for path in files:
        root = ET.parse(path).getroot()
        for suite in ([root] if root.tag == "testsuite" else root.findall(".//testsuite")):
            if any(int(suite.get(key, "0")) for key in ("errors", "failures", "skipped")):
                raise ValueError("截图测试报告包含错误、失败或跳过")
            cases = suite.findall("testcase")
            if int(suite.get("tests", "0")) != len(cases):
                raise ValueError("截图测试报告项目数不一致")
            for case in cases:
                if any(case.find(tag) is not None for tag in ("error", "failure", "skipped")):
                    raise ValueError("截图测试报告包含未通过的测试")
                observed[case.get("classname", "").rsplit(".", 1)[-1]] += 1
    if set(observed) != set(classes):
        raise ValueError(f"截图类与报告不一致：期望 {sorted(classes)}，实际 {sorted(observed)}")
    print(f"截图报告通过：{sum(observed.values())} 项，{len(observed)} 个源类")
    for name in sorted(observed):
        print(f"{name}: {observed[name]}")


if __name__ == "__main__":
    main()
