import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class JavaDetectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android build ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "project/android"
        self.root.mkdir(parents=True)
        self.script = self.root / "build.sh"
        shutil.copyfile(Path(__file__).with_name("build.sh"), self.script)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for command in ("dirname", "readlink"):
            (self.bin / command).symlink_to(shutil.which(command))
        self.executable(
            self.bin / "rustup",
            'printf "DETECTED_JAVA_HOME=%s\\n" "$JAVA_HOME"\nexit 42',
        )
        self.ndk = self.root / "ndk"
        self.executable(
            self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang",
            "exit 0",
        )

    def executable(self, path, body):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/bash\n" + body + "\n")
        path.chmod(0o755)

    def jdk(self, name, version="25.0.1"):
        home = self.root / name
        self.executable(home / "bin/javac", f'echo "javac {version}"')
        self.executable(home / "bin/java", "exit 0")
        return home

    def run_build(self, home=None, mode="debug"):
        environment = dict(os.environ)
        environment.pop("JAVA_HOME", None)
        environment.update(PATH=str(self.bin), ANDROID_NDK_HOME=str(self.ndk))
        if home is not None:
            environment["JAVA_HOME"] = str(home)
        return subprocess.run(
            ["/bin/bash", str(self.script), mode, "x86_64"],
            env=environment,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def assert_detected(self, result, home):
        # Stop at the first Rust command without compiling or downloading anything.
        self.assertEqual(42, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"DETECTED_JAVA_HOME={home}", result.stdout)

    def test_detects_jdk_through_alternatives_symlinks(self):
        home = self.jdk("jdk 25")
        alternative = self.root / "alternatives/javac"
        alternative.parent.mkdir()
        alternative.symlink_to(home / "bin/javac")
        (self.bin / "javac").symlink_to(alternative)
        self.assert_detected(self.run_build(), home)

    def test_valid_java_home_overrides_path(self):
        selected = self.jdk("jdk21", "21.0.13")
        other = self.jdk("jdk25")
        (self.bin / "javac").symlink_to(other / "bin/javac")
        self.assert_detected(self.run_build(selected), selected)

    def test_stale_java_home_warns_and_detects_from_path(self):
        home = self.jdk("jdk25")
        (self.bin / "javac").symlink_to(home / "bin/javac")
        result = self.run_build(self.root / "missing-jdk")
        self.assert_detected(result, home)
        self.assertIn("Ignoring JAVA_HOME=", result.stderr)

    def test_missing_compiler_explains_how_to_install_jdk(self):
        result = self.run_build()
        self.assertEqual(1, result.returncode)
        self.assertIn("No JDK found. Install JDK 17 or newer", result.stderr)

    def test_jre_only_home_falls_back_to_full_jdk(self):
        jre = self.root / "jre"
        self.executable(jre / "bin/java", "exit 0")
        home = self.jdk("jdk25")
        (self.bin / "javac").symlink_to(home / "bin/javac")
        self.assert_detected(self.run_build(jre), home)

    def test_old_jdk_is_rejected(self):
        result = self.run_build(self.jdk("jdk8", "1.8.0_482"))
        self.assertEqual(1, result.returncode)
        self.assertIn("JDK 17 or newer is required", result.stderr)

    def test_jdk17_is_accepted(self):
        home = self.jdk("jdk17", "17.0.17")
        self.assert_detected(self.run_build(home), home)

    def test_compiler_failure_is_reported(self):
        home = self.jdk("broken-jdk")
        self.executable(home / "bin/javac", 'echo "compiler failed" >&2\nexit 1')
        result = self.run_build(home)
        self.assertEqual(1, result.returncode)
        self.assertIn("Cannot run", result.stderr)
        self.assertIn("compiler failed", result.stderr)

    def test_unknown_version_is_rejected(self):
        result = self.run_build(self.jdk("unknown-jdk", "unknown"))
        self.assertEqual(1, result.returncode)
        self.assertIn("JDK 17 or newer is required", result.stderr)

    def prepare_native_build(self):
        for command in ("mkdir", "cp"):
            (self.bin / command).symlink_to(shutil.which(command))
        self.executable(self.bin / "rustup", "exit 0")
        self.executable(
            self.bin / "cargo",
            'profile=debug\n'
            'for arg in "$@"; do\n'
            '  if [[ "$arg" == --release ]]; then profile=release; fi\n'
            'done\n'
            'output="$CARGO_TARGET_DIR/x86_64-linux-android/$profile"\n'
            'mkdir -p "$output"\n'
            'printf "native symbols\\n" > "$output/libspaces_android.so"',
        )
        self.executable(
            self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip",
            'echo "STRIP_NATIVE_SYMBOLS"',
        )
        self.executable(self.root / "gradlew", "exit 0")

    def test_debug_build_preserves_native_symbols(self):
        self.prepare_native_build()
        result = self.run_build(self.jdk("jdk25"))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("STRIP_NATIVE_SYMBOLS", result.stdout)
        self.assertEqual(
            "native symbols\n",
            (self.root / "app/src/debug/jniLibs/x86_64/libspaces_android.so").read_text(),
        )

    def test_release_build_still_strips_native_symbols(self):
        self.prepare_native_build()
        result = self.run_build(self.jdk("jdk25"), mode="release")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("STRIP_NATIVE_SYMBOLS", result.stdout)


if __name__ == "__main__":
    unittest.main()
