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
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$toolchain/${compiler}26-clang" ]]; then
    echo "Missing NDK. See README.md for the pinned prerequisites." >&2
    exit 1
fi
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
    echo "Set JAVA_HOME to JDK 17 or 21." >&2
    exit 1
fi
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
