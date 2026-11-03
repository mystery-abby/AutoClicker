#!/usr/bin/env bash
# 一键打包 APK
#   用法: ./package.sh          # 打 release 包（使用 keystore.properties 签名）
#         ./package.sh debug    # 打 debug 包
#
# 说明: 本机 JAVA_HOME 默认指向 JDK 8，AGP 8.3 需要 JDK 17+，
#       脚本会自动在常见路径里找一个可用的 JDK，无需手动 export。

set -euo pipefail
cd "$(dirname "$0")"

TYPE="${1:-release}"
case "$TYPE" in
  release) TASK=assembleRelease ;;
  debug)   TASK=assembleDebug ;;
  *) echo "未知类型: $TYPE（只支持 release / debug）" >&2; exit 1 ;;
esac

# ---------- 1. 查找 JDK 17+ ----------
java_ok() {
  local home="$1"
  [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
  "$home/bin/java" -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'
}

JDK=""
for candidate in \
  "${JAVA_HOME:-}" \
  /c/Program\ Files/Java/jdk-2* \
  /c/Program\ Files/Java/jdk-17* \
  "/c/Program Files/Android/Android Studio/jbr" \
  /usr/lib/jvm/* ; do
  [ -e "$candidate" ] || continue
  if java_ok "$candidate"; then JDK="$candidate"; break; fi
done

if [ -z "$JDK" ]; then
  echo "未找到 JDK 17 及以上版本，请安装 JDK 17/21 或设置 JAVA_HOME。" >&2
  exit 1
fi
export JAVA_HOME="$JDK"
echo "使用 JDK: $JAVA_HOME"

# ---------- 2. 构建 ----------
echo "构建 $TYPE 包 ..."
# Gradle 的 FileLockCommunicator 会随机绑定一个 UDP 端口用于守护进程间通信，
# 而 Windows 的 excludedportrange 预留了大量端口段，偶尔会撞上抛 BindException。
# 这不是构建本身的问题，重启守护进程后重试即可。
build_ok=0
for attempt in 1 2 3 4 5; do
  if ./gradlew "$TASK" --console=plain; then
    build_ok=1
    break
  fi
  echo "第 $attempt 次构建失败，尝试重启 Gradle 守护进程后重试..." >&2
  ./gradlew --stop >/dev/null 2>&1 || true
  sleep 2
done

if [ "$build_ok" -ne 1 ]; then
  echo "构建失败。" >&2
  exit 1
fi

SRC="app/build/outputs/apk/$TYPE/app-$TYPE.apk"
if [ ! -f "$SRC" ]; then
  echo "未找到产物: $SRC" >&2
  exit 1
fi

# ---------- 3. 复制到项目根目录 ----------
cp -f "$SRC" "自动点击器.apk"
SIZE=$(stat -c %s "自动点击器.apk" 2>/dev/null || stat -f %z "自动点击器.apk")
echo
echo "完成: $(pwd)/自动点击器.apk"
echo "大小: $SIZE 字节"
echo "MD5 : $(md5sum "自动点击器.apk" | awk '{print $1}')"
