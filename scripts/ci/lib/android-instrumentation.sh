#!/usr/bin/env bash

# 仅解析 am instrument 的已落盘输出；调用方仍需单独验证目标进程状态。
nexara_assert_normal_instrumentation_output() {
    local output_file="$1"
    local command_status="$2"
    if [[ "${command_status}" != "0" ]]; then
        echo "am instrument 异常退出（${command_status}）：${output_file}" >&2
        return 1
    fi
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed|AssertionError' "${output_file}"; then
        echo "instrumentation 输出包含失败标记：${output_file}" >&2
        return 1
    fi
    if ! grep -Eq '^OK \([0-9]+ tests?\)$' "${output_file}"; then
        echo "instrumentation 输出缺少 JUnit OK 汇总：${output_file}" >&2
        return 1
    fi
    if ! grep -Eq '^INSTRUMENTATION_CODE: -1$' "${output_file}"; then
        echo "instrumentation 输出缺少成功状态码：${output_file}" >&2
        return 1
    fi
}

nexara_assert_expected_process_death_output() {
    local output_file="$1"
    local command_status="$2"
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|AssertionError|relay did not terminate main process' "${output_file}"; then
        echo "预期进程死亡阶段出现断言或协议失败：${output_file}" >&2
        return 1
    fi
    if grep -Eq '^OK \([0-9]+ tests?\)$' "${output_file}"; then
        echo "预期进程死亡阶段意外正常返回：${output_file}" >&2
        return 1
    fi
    if [[ "${command_status}" == "124" ]]; then
        echo "预期进程死亡阶段超时：${output_file}" >&2
        return 1
    fi
    if ! grep -Eq 'Process crashed|shortMsg=Process crashed|INSTRUMENTATION_STATUS: class=com\.promenar\.nexara\.data\.backup\.AndroidRestoreRelayEndToEndTest' "${output_file}"; then
        echo "预期进程死亡阶段没有进入目标测试：${output_file}" >&2
        return 1
    fi
}
