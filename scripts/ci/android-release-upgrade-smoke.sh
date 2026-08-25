#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "用法: $0 OLD_SIGNED_APK NEW_SIGNED_APK" >&2
    exit 2
fi

OLD_APK="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
NEW_APK="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
PACKAGE="com.promenar.nexara.native"
SERIAL="${ANDROID_SERIAL:-}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

[[ -f "${OLD_APK}" && -f "${NEW_APK}" ]] || {
    echo "旧版或新版 APK 不存在" >&2
    exit 1
}
[[ -n "${SERIAL}" ]] || {
    echo "必须通过 ANDROID_SERIAL 明确指定专用测试设备" >&2
    exit 1
}
[[ -n "${SDK_ROOT}" ]] || {
    echo "ANDROID_SDK_ROOT/ANDROID_HOME 未配置" >&2
    exit 1
}
command -v sqlite3 >/dev/null || {
    echo "缺少 sqlite3，无法建立并回读 Room 升级哨兵" >&2
    exit 1
}
command -v python3 >/dev/null || {
    echo "缺少 python3，无法构造 WAL-only 覆盖升级哨兵" >&2
    exit 1
}

BUILD_TOOLS_DIR="$(find "${SDK_ROOT}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
AAPT="${BUILD_TOOLS_DIR}/aapt"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
ADB="${SDK_ROOT}/platform-tools/adb"
for tool in "${AAPT}" "${APKSIGNER}" "${ADB}"; do
    [[ -x "${tool}" ]] || { echo "缺少 Android 工具: ${tool}" >&2; exit 1; }
done

adb_cmd() { "${ADB}" -s "${SERIAL}" "$@"; }

wait_for_upgraded_startup() {
    local upgraded_db="$1" identity_marker="$2"
    local timeout_seconds="${NEXARA_UPGRADE_STARTUP_TIMEOUT_SECONDS:-30}"
    local probe_db="${tmp_root}/upgrade-ready-probe.db"
    [[ "${timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || {
        echo "NEXARA_UPGRADE_STARTUP_TIMEOUT_SECONDS 必须为正整数" >&2
        return 1
    }
    local deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        if [[ -z "$(adb_cmd shell pidof "${PACKAGE}" | tr -d '\r')" ]]; then
            echo "新版进程在升级就绪前退出" >&2
            return 1
        fi
        if [[ "$(adb_cmd shell test -f "${upgraded_db}" && echo yes | tr -d '\r')" == "yes" ]]; then
            adb_cmd pull "${upgraded_db}" "${probe_db}" >/dev/null 2>&1 || true
            for suffix in -wal -shm; do
                adb_cmd pull "${upgraded_db}${suffix}" "${probe_db}${suffix}" >/dev/null 2>&1 || true
            done
            if [[ "$(sqlite3 "${probe_db}" 'PRAGMA user_version;' 2>/dev/null || true)" == "18" ]] &&
                [[ "$(adb_cmd shell test -f "${identity_marker}" && echo yes | tr -d '\r')" == "yes" ]]; then
                return 0
            fi
        fi
        sleep 1
    done
    echo "升级启动在 ${timeout_seconds}s 内未完成 v18 数据库与工作区身份认领" >&2
    return 1
}

launch_package() {
    local component
    component="$(adb_cmd shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        -p "${PACKAGE}" | tr -d '\r' | tail -1)"
    [[ "${component}" == "${PACKAGE}/"* ]] || {
        echo "无法解析可启动 Activity：${component}" >&2
        return 1
    }
    adb_cmd shell am start -W -n "${component}" >/dev/null
}

apk_field() {
    local apk="$1" field="$2"
    "${AAPT}" dump badging "${apk}" \
        | sed -n "s/^package:.*[[:space:]]${field}='\([^']*\)'.*$/\1/p" \
        | head -1
}

old_package="$(apk_field "${OLD_APK}" name)"
new_package="$(apk_field "${NEW_APK}" name)"
old_code="$(apk_field "${OLD_APK}" versionCode)"
new_code="$(apk_field "${NEW_APK}" versionCode)"
old_name="$(apk_field "${OLD_APK}" versionName)"
new_name="$(apk_field "${NEW_APK}" versionName)"
[[ "${old_package}" == "${PACKAGE}" && "${new_package}" == "${PACKAGE}" ]] || {
    echo "APK 包名不一致" >&2
    exit 1
}
[[ "${old_code}" =~ ^[0-9]+$ && "${new_code}" =~ ^[0-9]+$ && "${new_code}" -gt "${old_code}" ]] || {
    echo "新版 versionCode 必须严格递增" >&2
    exit 1
}

certificate_sha256() {
    "${APKSIGNER}" verify --verbose --print-certs "$1" \
        | sed -n -E 's/^(Signer #[0-9]+|V[0-9]+ Signer):? certificate SHA-256 digest: ([[:xdigit:]]{64})$/\2/p' \
        | tr '[:upper:]' '[:lower:]' \
        | sort -u
}

old_cert="$(certificate_sha256 "${OLD_APK}")"
new_cert="$(certificate_sha256 "${NEW_APK}")"
[[ "${old_cert}" =~ ^[0-9a-f]{64}$ && "${old_cert}" == "${new_cert}" ]] || {
    echo "旧版与新版 APK 签名证书不一致" >&2
    exit 1
}

adb_cmd get-state >/dev/null
# 只在写入升级哨兵之前清理专用测试设备上的目标包；覆盖升级阶段禁止卸载或清数据。
adb_cmd uninstall "${PACKAGE}" >/dev/null 2>&1 || true
adb_cmd install --no-streaming "${OLD_APK}" >/dev/null
launch_package
adb_cmd shell sleep 3
adb_cmd root >/dev/null
adb_cmd wait-for-device
adb_cmd shell am force-stop "${PACKAGE}"

data_dir="$(adb_cmd shell dumpsys package "${PACKAGE}" | sed -n 's/^[[:space:]]*dataDir=//p' | tr -d '\r' | head -1)"
[[ "${data_dir}" == /data/*/${PACKAGE} ]] || {
    echo "无法解析受控应用 dataDir" >&2
    exit 1
}
app_uid="$(adb_cmd shell stat -c %u "${data_dir}" | tr -d '\r')"
app_gid="$(adb_cmd shell stat -c %g "${data_dir}" | tr -d '\r')"
[[ "${app_uid}" =~ ^[0-9]+$ && "${app_gid}" =~ ^[0-9]+$ ]] || {
    echo "无法解析应用沙箱 owner" >&2
    exit 1
}

tmp_root="$(mktemp -d)"
cleanup() {
    find "${tmp_root}" -type f -exec chmod u+w {} + 2>/dev/null || true
    rm -rf "${tmp_root}"
}
trap cleanup EXIT

remote_db="${data_dir}/databases/nexara.db"
[[ "$(adb_cmd shell test -f "${remote_db}" && echo yes | tr -d '\r')" == "yes" ]] || {
    echo "公开旧版启动后未创建 nexara.db" >&2
    exit 1
}
db_name="$(basename "${remote_db}")"
adb_cmd pull "${remote_db}" "${tmp_root}/${db_name}" >/dev/null
for suffix in -wal -shm; do
    adb_cmd pull "${remote_db}${suffix}" "${tmp_root}/${db_name}${suffix}" >/dev/null 2>&1 || true
done
[[ "$(sqlite3 "${tmp_root}/${db_name}" 'PRAGMA user_version;')" == "17" ]] || {
    echo "公开旧版 Room schema 不是实测 v17" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/${db_name}" 'SELECT identity_hash FROM room_master_table WHERE id=42;')" == "3311ec5f07e8df42c02fd09163c49f6e" ]] || {
    echo "公开旧版 Room identity hash 不匹配" >&2
    exit 1
}

workspace_root="${data_dir}/files/workspaces/upgrade-session"
sqlite3 "${tmp_root}/${db_name}" <<SQL
PRAGMA foreign_keys=OFF;
BEGIN IMMEDIATE;
INSERT OR REPLACE INTO agents(
  id,name,description,system_prompt,model,icon,color,
  is_pinned,created_at,use_inherited_config
) VALUES('upgrade-agent','Upgrade Agent','upgrade sentinel','','','','',0,1700000000000,1);
INSERT OR REPLACE INTO sessions(
  id,agent_id,title,last_message,unread,is_pinned,workspace_path,workspace_root_uuid,created_at,updated_at
) VALUES('upgrade-session','upgrade-agent','Upgrade Session','UPGRADE_MESSAGE_SENTINEL',0,0,
  '${workspace_root}','upgrade-root',1700000000001,1700000000002);
INSERT OR REPLACE INTO messages(id,session_id,role,content,attachments,created_at)
VALUES('upgrade-message','upgrade-session','user','UPGRADE_MESSAGE_SENTINEL',
  '[{"uri":"content://upgrade/attachment","fileName":"UPGRADE_ATTACHMENT_SENTINEL","sizeBytes":26}]',
  1700000000003);
INSERT OR REPLACE INTO workspace_files(
  uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,
  vector_version,kg_version,in_recycle_bin,created_at,updated_at
) VALUES('upgrade-root','upgrade-sentinel-root','',0,1,
  '${workspace_root}','/',1,1,0,1700000000004,1700000000005);
INSERT OR REPLACE INTO workspace_files(
  uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,
  materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at
) VALUES('upgrade-file','upgrade-root','upgrade.txt',
  '64124d620f1875ac448941c5c8161bfc7f4bdabdc592f0b0d53e59530623b232',26,0,
  '${workspace_root}','/upgrade.txt',1,1,0,1700000000006,1700000000007);
INSERT OR REPLACE INTO vectorization_tasks(
  id,type,status,doc_id,doc_title,session_id,last_chunk_index,progress,created_at,updated_at
) VALUES('upgrade-vector-task','document_reference','completed','upgrade-file','upgrade.txt',
  'upgrade-session',0,1.0,1700000000008,1700000000009);
COMMIT;
PRAGMA wal_checkpoint(TRUNCATE);
SQL

wal_fixture="${tmp_root}/wal-fixture"
mkdir -p "${wal_fixture}"
python3 - "${tmp_root}/${db_name}" "${wal_fixture}" <<'PY'
import shutil
import sqlite3
import sys
from pathlib import Path

database = Path(sys.argv[1])
fixture = Path(sys.argv[2])
connection = sqlite3.connect(database)
try:
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA wal_autocheckpoint=0")
    connection.execute(
        "UPDATE messages SET status='WAL_ONLY_SENTINEL' WHERE id='upgrade-message'"
    )
    connection.commit()
    shutil.copy2(database, fixture / database.name)
    shutil.copy2(Path(str(database) + "-wal"), fixture / f"{database.name}-wal")
    shutil.copy2(Path(str(database) + "-shm"), fixture / f"{database.name}-shm")
finally:
    connection.close()
PY

cat >"${tmp_root}/nexara_provider.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="protocol_id">Generic_OpenAI_Compat</string>
    <string name="protocol_id_name">OpenAI Compatible</string>
    <string name="base_url">https://upgrade.invalid/v1/chat/completions</string>
    <string name="model">upgrade-model</string>
    <string name="provider_name">Upgrade Provider</string>
</map>
XML
cat >"${tmp_root}/nexara_settings.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="user_name">UPGRADE_USER_SENTINEL</string>
</map>
XML
printf '%s' 'UPGRADE_WORKSPACE_SENTINEL' >"${tmp_root}/upgrade.txt"

adb_cmd shell mkdir -p "${data_dir}/shared_prefs" "${workspace_root}"
adb_cmd push "${wal_fixture}/${db_name}" "${remote_db}" >/dev/null
adb_cmd push "${wal_fixture}/${db_name}-wal" "${remote_db}-wal" >/dev/null
adb_cmd push "${wal_fixture}/${db_name}-shm" "${remote_db}-shm" >/dev/null
adb_cmd push "${tmp_root}/nexara_provider.xml" "${data_dir}/shared_prefs/nexara_provider.xml" >/dev/null
adb_cmd push "${tmp_root}/nexara_settings.xml" "${data_dir}/shared_prefs/nexara_settings.xml" >/dev/null
adb_cmd push "${tmp_root}/upgrade.txt" "${workspace_root}/upgrade.txt" >/dev/null
adb_cmd shell chown "${app_uid}:${app_gid}" \
    "${remote_db}" \
    "${remote_db}-wal" \
    "${remote_db}-shm" \
    "${data_dir}/shared_prefs/nexara_provider.xml" \
    "${data_dir}/shared_prefs/nexara_settings.xml" \
    "${workspace_root}" \
    "${workspace_root}/upgrade.txt"
adb_cmd shell chmod 660 "${remote_db}" "${remote_db}-wal" "${remote_db}-shm" \
    "${data_dir}/shared_prefs/nexara_provider.xml" "${data_dir}/shared_prefs/nexara_settings.xml"
adb_cmd shell chmod 700 "${workspace_root}"
adb_cmd shell chmod 600 "${workspace_root}/upgrade.txt"
# root/adb 注入会继承宿主临时文件或上一轮安装的 MCS 类别；只改 Unix owner 不足以
# 模拟真实应用数据。恢复包沙箱标签后再覆盖升级，否则会把 SELinux 拒绝误报为迁移失败。
adb_cmd shell restorecon -RF "${data_dir}"
data_context="$(adb_cmd shell ls -Zd "${data_dir}" | awk '{print $1}' | tr -d '\r')"
[[ "${data_context}" == u:object_r:app_data_file:s0:* ]] || {
    echo "无法解析应用沙箱 SELinux MCS 标签" >&2
    exit 1
}
# restorecon 只恢复 app_data_file 类型，不会把 root 新建节点的 MCS category 自动改成
# 当前包 category；显式复制 dataDir 的完整上下文，随后逐项读回校验。
adb_cmd shell chcon "${data_context}" \
    "${remote_db}" \
    "${remote_db}-wal" \
    "${remote_db}-shm" \
    "${data_dir}/shared_prefs/nexara_provider.xml" \
    "${data_dir}/shared_prefs/nexara_settings.xml"
adb_cmd shell chcon -R "${data_context}" "${data_dir}/files/workspaces"
for injected_path in \
    "${remote_db}" \
    "${remote_db}-wal" \
    "${remote_db}-shm" \
    "${data_dir}/shared_prefs/nexara_provider.xml" \
    "${data_dir}/shared_prefs/nexara_settings.xml" \
    "${data_dir}/files/workspaces" \
    "${workspace_root}" \
    "${workspace_root}/upgrade.txt"; do
    injected_context="$(adb_cmd shell ls -Zd "${injected_path}" | awk '{print $1}' | tr -d '\r')"
    [[ -n "${data_context}" && "${injected_context}" == "${data_context}" ]] || {
        echo "升级哨兵 SELinux 标签未恢复到应用沙箱: ${injected_path}" >&2
        exit 1
    }
done

first_install_before="$(adb_cmd shell dumpsys package "${PACKAGE}" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | tr -d '\r' | head -1)"
[[ -n "${first_install_before}" ]] || { echo "无法读取升级前 firstInstallTime" >&2; exit 1; }

# 覆盖升级核心动作：这里禁止任何 uninstall、pm clear 或失败后的冷装回退。
adb_cmd install -r --no-streaming "${NEW_APK}" >/dev/null
first_install_after="$(adb_cmd shell dumpsys package "${PACKAGE}" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | tr -d '\r' | head -1)"
[[ "${first_install_before}" == "${first_install_after}" ]] || {
    echo "覆盖升级改变了 firstInstallTime，数据继承失败" >&2
    exit 1
}
installed_dump="$(adb_cmd shell dumpsys package "${PACKAGE}")"
grep -Eq "versionCode=${new_code}([[:space:]]|$)" <<<"${installed_dump}" || {
    echo "覆盖升级后 versionCode 不匹配" >&2
    exit 1
}

launch_package
upgraded_remote_db="${data_dir}/databases/nexara_v2.db"
wait_for_upgraded_startup "${upgraded_remote_db}" "${workspace_root}/.nexara_root_identity"
adb_cmd shell am force-stop "${PACKAGE}"
[[ "$(adb_cmd shell test -f "${upgraded_remote_db}" && echo yes | tr -d '\r')" == "yes" ]] || {
    echo "新版未接管公开旧库到 nexara_v2.db" >&2
    exit 1
}
adb_cmd pull "${upgraded_remote_db}" "${tmp_root}/upgraded.db" >/dev/null
for suffix in -wal -shm; do
    adb_cmd pull "${upgraded_remote_db}${suffix}" "${tmp_root}/upgraded.db${suffix}" >/dev/null 2>&1 || true
done

[[ "$(sqlite3 "${tmp_root}/upgraded.db" 'PRAGMA user_version;')" == "18" ]] || {
    echo "覆盖升级后 Room schema 不是 v18" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" 'SELECT identity_hash FROM room_master_table WHERE id=42;')" == "c32f59706ea482fd5697681569c6fdb5" ]] || {
    echo "覆盖升级后 Room identity hash 不匹配 v18 导出 schema" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT name FROM agents WHERE id='upgrade-agent';")" == "Upgrade Agent" ]] || {
    echo "Agent 哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT content FROM messages WHERE id='upgrade-message';")" == "UPGRADE_MESSAGE_SENTINEL" ]] || {
    echo "会话消息哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT status FROM messages WHERE id='upgrade-message';")" == "WAL_ONLY_SENTINEL" ]] || {
    echo "仅存在于公开旧库 WAL 的哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT legacy_attachments FROM messages WHERE id='upgrade-message';")" == *"UPGRADE_ATTACHMENT_SENTINEL"* ]] || {
    echo "旧消息附件列哨兵未被保全" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT title FROM sessions WHERE id='upgrade-session';")" == "Upgrade Session" ]] || {
    echo "会话哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT name FROM workspace_files WHERE uuid='upgrade-file';")" == "upgrade.txt" ]] || {
    echo "工作区数据库哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT workspace_root_uuid FROM workspace_files WHERE uuid='upgrade-file';")" == "upgrade-root" ]] || {
    echo "工作区根映射哨兵丢失" >&2
    exit 1
}
[[ -n "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT hash FROM workspace_files WHERE uuid='upgrade-root';")" ]] || {
    echo "公开 v17 工作区根未经过真实仓库身份认领" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT workspace_root_uuid FROM vectorization_tasks WHERE id='upgrade-vector-task';")" == "upgrade-root" ]] || {
    echo "向量任务工作区哨兵丢失" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" 'PRAGMA foreign_key_check;' | wc -l | tr -d ' ')" == "0" ]] || {
    echo "覆盖升级后数据库外键校验失败" >&2
    exit 1
}
[[ "$(adb_cmd shell cat "${workspace_root}/upgrade.txt" | tr -d '\r')" == "UPGRADE_WORKSPACE_SENTINEL" ]] || {
    echo "工作区物理文件哨兵丢失" >&2
    exit 1
}
[[ "$(adb_cmd shell test -f "${workspace_root}/.nexara_root_identity" && echo yes | tr -d '\r')" == "yes" ]] || {
    echo "公开 v17 工作区未建立运行时身份标记" >&2
    exit 1
}
workspace_sha="$(adb_cmd shell toybox sha256sum "${workspace_root}/upgrade.txt" | awk '{print $1}' | tr -d '\r')"
[[ "${workspace_sha}" == "64124d620f1875ac448941c5c8161bfc7f4bdabdc592f0b0d53e59530623b232" ]] || {
    echo "工作区物理文件哈希不匹配" >&2
    exit 1
}
adb_cmd pull "${data_dir}/shared_prefs/nexara_provider.xml" "${tmp_root}/provider-after.xml" >/dev/null
adb_cmd pull "${data_dir}/shared_prefs/nexara_settings.xml" "${tmp_root}/settings-after.xml" >/dev/null
grep -Fq 'Generic_OpenAI_Compat' "${tmp_root}/provider-after.xml" || { echo "Provider 协议哨兵丢失" >&2; exit 1; }
grep -Fq 'https://upgrade.invalid/v1/chat/completions' "${tmp_root}/provider-after.xml" || { echo "Provider 地址哨兵丢失" >&2; exit 1; }
grep -Fq 'upgrade-model' "${tmp_root}/provider-after.xml" || { echo "Provider 模型哨兵丢失" >&2; exit 1; }
grep -Fq 'Upgrade Provider' "${tmp_root}/provider-after.xml" || { echo "Provider 偏好哨兵丢失" >&2; exit 1; }
grep -Fq 'UPGRADE_USER_SENTINEL' "${tmp_root}/settings-after.xml" || { echo "用户偏好哨兵丢失" >&2; exit 1; }

echo "UPGRADE_SMOKE=PASS"
echo "PACKAGE=${PACKAGE}"
echo "OLD_VERSION=${old_name}(${old_code})"
echo "NEW_VERSION=${new_name}(${new_code})"
echo "FIRST_INSTALL_TIME=${first_install_after}"
echo "SIGNER_SHA256=${new_cert}"
