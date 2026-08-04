@echo off
REM TaskCopilot 启动脚本（Windows）
REM 自动查找当前目录下最新的 TaskCopilot-*.jar 并运行
setlocal enabledelayedexpansion
set JAR=
for %%f in (TaskCopilot-*.jar) do set JAR=%%f
if not defined JAR (
    echo 未找到 TaskCopilot-*.jar，请先下载发布包或执行 mvnw package 打包。
    pause
    exit /b 1
)
echo 正在启动 %JAR% ...
java -jar "%JAR%"
pause
