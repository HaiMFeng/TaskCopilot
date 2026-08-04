#!/usr/bin/env bash
# TaskCopilot 启动脚本（Linux / macOS）
# 自动查找当前目录下最新的 TaskCopilot-*.jar 并运行
set -e
JAR=$(ls TaskCopilot-*.jar 2>/dev/null | head -n 1 || true)
if [ -z "$JAR" ]; then
    echo "未找到 TaskCopilot-*.jar，请先下载发布包或执行 ./mvnw package 打包。"
    exit 1
fi
echo "正在启动 $JAR ..."
java -jar "$JAR"
