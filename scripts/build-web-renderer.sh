#!/bin/bash
# build-web-renderer.sh
# 构建 web-renderer 并将产物复制到 assets 目录供 Metro 打包

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB_RENDERER_DIR="$PROJECT_ROOT/src/web-renderer"
OUTPUT_DIR="$PROJECT_ROOT/assets/web-renderer"

echo "🔨 构建 web-renderer..."
cd "$WEB_RENDERER_DIR"

# 安装依赖（如果 node_modules 不存在）
if [ ! -d "node_modules" ]; then
  echo "📦 安装依赖..."
  npm install
fi

# 构建
npm run build

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 复制产物（使用 .bundle 扩展名，Metro 已配置支持）
cp "$WEB_RENDERER_DIR/dist/index.html" "$OUTPUT_DIR/web-renderer.bundle"

SIZE=$(wc -c < "$OUTPUT_DIR/web-renderer.bundle" | tr -d ' ')
echo "✅ 构建完成！产物大小: $(( SIZE / 1024 ))KB"
echo "📁 产物位置: $OUTPUT_DIR/web-renderer.bundle"
