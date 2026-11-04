#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mode="${1:-debug}"
abi="${2:-arm64-v8a}"
case "$mode" in
    debug) profile=debug; cargo_flags=(); task=assembleDebug ;;
    release) profile=release; cargo_flags=(--release); task=assembleRelease ;;
    *) echo "Usage: $0 [debug|release] [arm64-v8a|x86_64]" >&2; exit 2 ;;
esac
case "$abi" in
    arm64-v8a) target=aarch64-linux-android; compiler=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android; compiler=x86_64-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
esac

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$PWD/.tools/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$toolchain/${compiler}26-clang" ]]; then
    echo "Missing NDK. See README.md for the pinned prerequisites." >&2
    exit 1
fi
if [[ -n "${JAVA_HOME:-}" && ( ! -x "$JAVA_HOME/bin/javac" || ! -x "$JAVA_HOME/bin/java" ) ]]; then
    echo "Ignoring JAVA_HOME=$JAVA_HOME: it does not contain a full JDK. Detecting javac on PATH." >&2
    unset JAVA_HOME
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
    if ! javac_path="$(command -v javac)"; then
        echo "No JDK found. Install JDK 17 or newer and put javac on PATH, or set JAVA_HOME." >&2
        exit 1
    fi
    javac_path="$(readlink -f "$javac_path")"
    export JAVA_HOME="${javac_path%/bin/javac}"
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Cannot find java alongside javac in $JAVA_HOME/bin. Set JAVA_HOME to a full JDK." >&2
    exit 1
fi
if ! java_version="$("$JAVA_HOME/bin/javac" -version 2>&1)"; then
    echo "Cannot run $JAVA_HOME/bin/javac: $java_version" >&2
    exit 1
fi
if [[ ! "$java_version" =~ ^javac[[:space:]]+([0-9]+) || "${BASH_REMATCH[1]}" -lt 17 ]]; then
    echo "JDK 17 or newer is required; found $java_version in $JAVA_HOME." >&2
    exit 1
fi
export JAVA_HOME
echo "Using $java_version from $JAVA_HOME"
export PATH="$toolchain:$JAVA_HOME/bin:$PATH"
target_env="${target//-/_}"
linker_env="CARGO_TARGET_${target_env^^}_LINKER"
export "$linker_env=$toolchain/${compiler}26-clang"
export "CC_${target_env}=$toolchain/${compiler}26-clang"
export "AR_${target_env}=$toolchain/llvm-ar"
export "CXX_${target_env}=$toolchain/${compiler}26-clang++"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
export CARGO_TARGET_DIR="$PWD/../../target/android"
rustup target add "$target"
cargo build --locked -p encrypted-spaces-android --target "$target" "${cargo_flags[@]}"
mkdir -p "app/src/$mode/jniLibs/$abi"
cp "$CARGO_TARGET_DIR/$target/$profile/libspaces_android.so" "app/src/$mode/jniLibs/$abi/"
"$toolchain/llvm-strip" --strip-debug "app/src/$mode/jniLibs/$abi/libspaces_android.so"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-home}"
./gradlew --no-daemon --console=plain "-PspacesAbi=$abi" "$task"
