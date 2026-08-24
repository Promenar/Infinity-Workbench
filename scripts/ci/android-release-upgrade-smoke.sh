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

BUILD_TOOLS_DIR="$(find "${SDK_ROOT}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
AAPT="${BUILD_TOOLS_DIR}/aapt"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
ADB="${SDK_ROOT}/platform-tools/adb"
for tool in "${AAPT}" "${APKSIGNER}" "${ADB}"; do
    [[ -x "${tool}" ]] || { echo "缺少 Android 工具: ${tool}" >&2; exit 1; }
done

adb_cmd() { "${ADB}" -s "${SERIAL}" "$@"; }

apk_field() {
    local apk="$1" field="$2"
    "${AAPT}" dump badging "${apk}" | sed -n "s/^package:.*${field}='\([^']*\)'.*$/\1/p" | head -1
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

old_cert="$("${APKSIGNER}" verify --print-certs "${OLD_APK}" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
new_cert="$("${APKSIGNER}" verify --print-certs "${NEW_APK}" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
[[ -n "${old_cert}" && "${old_cert}" == "${new_cert}" ]] || {
    echo "旧版与新版 APK 签名证书不一致" >&2
    exit 1
}

adb_cmd get-state >/dev/null
# 只在写入升级哨兵之前清理专用测试设备上的目标包；覆盖升级阶段禁止卸载或清数据。
adb_cmd uninstall "${PACKAGE}" >/dev/null 2>&1 || true
adb_cmd install --no-streaming "${OLD_APK}" >/dev/null
adb_cmd shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null
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

remote_db="$(adb_cmd shell find "${data_dir}/databases" -maxdepth 1 -type f -name '*.db' | tr -d '\r' | head -1)"
[[ "${remote_db}" == "${data_dir}/databases/"*.db ]] || {
    echo "旧版启动后未创建 Room 数据库" >&2
    exit 1
}
db_name="$(basename "${remote_db}")"
adb_cmd pull "${remote_db}" "${tmp_root}/${db_name}" >/dev/null
for suffix in -wal -shm; do
    adb_cmd pull "${remote_db}${suffix}" "${tmp_root}/${db_name}${suffix}" >/dev/null 2>&1 || true
done

workspace_root="${data_dir}/files/WorkSpace/upgrade-sentinel-root"
sqlite3 "${tmp_root}/${db_name}" <<SQL
PRAGMA foreign_keys=OFF;
BEGIN IMMEDIATE;
INSERT OR REPLACE INTO agents(
  id,name,description,name_customized,description_customized,system_prompt,model,icon,color,
  is_pinned,created_at,use_inherited_config
) VALUES('upgrade-agent','Upgrade Agent','upgrade sentinel',1,1,'','', '', '',0,1700000000000,1);
INSERT OR REPLACE INTO sessions(
  id,agent_id,title,last_message,unread,is_pinned,workspace_path,workspace_root_uuid,created_at,updated_at
) VALUES('upgrade-session','upgrade-agent','Upgrade Session','UPGRADE_MESSAGE_SENTINEL',0,0,
  '${workspace_root}','upgrade-root',1700000000001,1700000000002);
INSERT OR REPLACE INTO messages(id,session_id,role,content,created_at)
VALUES('upgrade-message','upgrade-session','user','UPGRADE_MESSAGE_SENTINEL',1700000000003);
INSERT OR REPLACE INTO workspace_files(
  uuid,workspace_root_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,
  vector_version,kg_version,in_recycle_bin,created_at,updated_at
) VALUES('upgrade-root','upgrade-root','upgrade-sentinel-root','',0,1,
  '${workspace_root}','/',1,1,0,1700000000004,1700000000005);
INSERT OR REPLACE INTO workspace_files(
  uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,
  materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at
) VALUES('upgrade-file','upgrade-root','upgrade-root','upgrade.txt',
  '64124d620f1875ac448941c5c8161bfc7f4bdabdc592f0b0d53e59530623b232',26,0,
  '${workspace_root}','/upgrade.txt',1,1,0,1700000000006,1700000000007);
COMMIT;
PRAGMA wal_checkpoint(TRUNCATE);
SQL
rm -f "${tmp_root}/${db_name}-wal" "${tmp_root}/${db_name}-shm"

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
adb_cmd push "${tmp_root}/${db_name}" "${remote_db}" >/dev/null
adb_cmd shell rm -f "${remote_db}-wal" "${remote_db}-shm"
adb_cmd push "${tmp_root}/nexara_provider.xml" "${data_dir}/shared_prefs/nexara_provider.xml" >/dev/null
adb_cmd push "${tmp_root}/nexara_settings.xml" "${data_dir}/shared_prefs/nexara_settings.xml" >/dev/null
adb_cmd push "${tmp_root}/upgrade.txt" "${workspace_root}/upgrade.txt" >/dev/null
adb_cmd shell chown "${app_uid}:${app_gid}" \
    "${remote_db}" \
    "${data_dir}/shared_prefs/nexara_provider.xml" \
    "${data_dir}/shared_prefs/nexara_settings.xml" \
    "${workspace_root}" \
    "${workspace_root}/upgrade.txt"
adb_cmd shell chmod 660 "${remote_db}" "${data_dir}/shared_prefs/nexara_provider.xml" "${data_dir}/shared_prefs/nexara_settings.xml"
adb_cmd shell chmod 700 "${workspace_root}"
adb_cmd shell chmod 600 "${workspace_root}/upgrade.txt"

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

adb_cmd shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null
adb_cmd shell sleep 4
adb_cmd shell am force-stop "${PACKAGE}"
adb_cmd pull "${remote_db}" "${tmp_root}/upgraded.db" >/dev/null
for suffix in -wal -shm; do
    adb_cmd pull "${remote_db}${suffix}" "${tmp_root}/upgraded.db${suffix}" >/dev/null 2>&1 || true
done

[[ "$(sqlite3 "${tmp_root}/upgraded.db" 'PRAGMA user_version;')" == "5" ]] || {
    echo "覆盖升级后 Room schema 不是 v5" >&2
    exit 1
}
[[ "$(sqlite3 "${tmp_root}/upgraded.db" "SELECT content FROM messages WHERE id='upgrade-message';")" == "UPGRADE_MESSAGE_SENTINEL" ]] || {
    echo "会话消息哨兵丢失" >&2
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
[[ "$(adb_cmd shell cat "${workspace_root}/upgrade.txt" | tr -d '\r')" == "UPGRADE_WORKSPACE_SENTINEL" ]] || {
    echo "工作区物理文件哨兵丢失" >&2
    exit 1
}
adb_cmd pull "${data_dir}/shared_prefs/nexara_provider.xml" "${tmp_root}/provider-after.xml" >/dev/null
adb_cmd pull "${data_dir}/shared_prefs/nexara_settings.xml" "${tmp_root}/settings-after.xml" >/dev/null
grep -Fq 'Upgrade Provider' "${tmp_root}/provider-after.xml" || { echo "Provider 偏好哨兵丢失" >&2; exit 1; }
grep -Fq 'UPGRADE_USER_SENTINEL' "${tmp_root}/settings-after.xml" || { echo "用户偏好哨兵丢失" >&2; exit 1; }

echo "UPGRADE_SMOKE=PASS"
echo "PACKAGE=${PACKAGE}"
echo "OLD_VERSION=${old_name}(${old_code})"
echo "NEW_VERSION=${new_name}(${new_code})"
echo "FIRST_INSTALL_TIME=${first_install_after}"
echo "SIGNER_SHA256=${new_cert}"
