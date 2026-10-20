"""Regression suite: historical MusicLab failures + negative guards."""
import importlib.util
import pathlib
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
TOOL = ROOT / "tools/universal-apk-builder"

def load(path, label):
    spec = importlib.util.spec_from_file_location(label, str(TOOL / path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

diag = load("classify-gradle-failure.py", "diagnostic")
guard = load("verify-changed-files.py", "scope_guard")

# Each item is an individual named unittest case, not a subTest aggregate.
CASES = [
    ("kotlin_unresolved", "e: file:///tmp/DiscogsDirectVersionBrowser.kt:3447:21 Unresolved reference 'archiveOriginalArtist'.", "KOTLIN"),
    ("kotlin_unresolved_null", "e: file:///tmp/DiscogsDirectVersionBrowser.kt:3447:49 Unresolved reference 'isNullOrBlank'.", "KOTLIN"),
    ("kotlin_compilation_task", "> Task :app:compileFossDebugKotlin FAILED", "KOTLIN"),
    ("kotlin_compile_generic", "Compilation error. See log for more details", "KOTLIN"),
    ("kotlin_with_signing_setup", "Set up signingConfig debug\nUnresolved reference 'archiveOriginalArtist'\n", "KOTLIN"),
    ("kotlin_with_keystore_setup", "Keystore restored from cache\nCompilation error", "KOTLIN"),
    ("kotlin_with_storefile_setup", "storeFile prepared\n> Task :app:compileFossDebugKotlin FAILED", "KOTLIN"),
    ("kotlin_with_validate_signing_name", "validateSigning was configured\nUnresolved reference 'x'", "KOTLIN"),
    ("kotlin_location_includes_line", "e: file:///tmp/AppService.kt:42:12 Unresolved reference 'abc'", "KOTLIN"),
    ("kotlin_ansi", "\x1b[31me: file:///tmp/Screen.kt:70:9 Unresolved reference 'bad'\x1b[0m", "KOTLIN"),
    ("java_compiler", "/workspace/src/Main.java:12: error: cannot find symbol", "JAVA"),
    ("java_gradle_task", "> Task :app:compileDebugJavaWithJavac FAILED", "JAVA"),
    ("java_with_signing_setup", "signing step configured\n> Task :app:compileDebugJavaWithJavac FAILED", "JAVA"),
    ("resource_linking", "Android resource linking failed", "RESOURCE"),
    ("resource_aapt", "AAPT: error: resource color/missing not found", "RESOURCE"),
    ("resource_task", "> Task :app:processFossDebugResources FAILED", "RESOURCE"),
    ("test_failed", "There were failing tests. See report.", "TEST"),
    ("test_assertion", "java.lang.AssertionError at Lab44ScoringAndTitleResolverTest.kt:30", "TEST"),
    ("test_summary", "# tests 49\n# pass 48\n# fail 1\n", "TEST"),
    ("test_kotlin", "AiCoverSearchEngineTest > some contract FAILED", "TEST"),
    ("test_failure_phrase", "Tests failed on validation", "TEST"),
    ("test_with_setup_keystore", "Keystore reused\nAssertionError", "TEST"),
    ("sign_keystore_tampered", "Keystore was tampered with, or password was incorrect", "SIGNING"),
    ("sign_cannot_recover_key", "Cannot recover key", "SIGNING"),
    ("sign_missing_keyfile", "Keystore file /tmp/key.jks not found for signing config", "SIGNING"),
    ("sign_no_alias", "No key with alias found", "SIGNING"),
    ("sign_task_failure", "> Task :app:validateSigningRelease FAILED", "SIGNING"),
    ("sign_actual_task_error", "Execution failed for task ':app:validateSigningDebug'.", "SIGNING"),
    ("sign_failed_to_sign", "Failed to sign APK", "SIGNING"),
    ("sdk_location", "SDK location not found.", "SDK"),
    ("sdk_android_not_found", "Android SDK not found", "SDK"),
    ("sdk_buildtools", "Failed to find Build Tools revision 35.0.0", "SDK"),
    ("sdk_android_home", "ANDROID_HOME is not configured", "SDK"),
    ("sdk_platform", "Platform SDK 35 not found", "SDK"),
    ("network_resolve", "Could not resolve all files for configuration", "NETWORK"),
    ("network_get", "Could not GET 'https://repo.example/a.jar'", "NETWORK"),
    ("network_head", "Could not HEAD 'https://repo.example/a.jar'", "NETWORK"),
    ("network_unknown_host", "java.net.UnknownHostException: repo.example", "NETWORK"),
    ("network_timeout", "Connection timed out", "NETWORK"),
    ("network_read_timeout", "Read timed out", "NETWORK"),
    ("disk_full", "No space left on device", "RESOURCES"),
    ("oom", "java.lang.OutOfMemoryError", "RESOURCES"),
    ("memory_heap", "Java heap space", "RESOURCES"),
    ("unknown_empty", "", "UNKNOWN"),
    ("unknown_setup_only", "setup-gradle cache: signing, keystore, storeFile\nBUILD FAILED", "UNKNOWN"),
    ("unknown_success", "BUILD SUCCESSFUL", "UNKNOWN"),
    ("unknown_gradle_no_details", "FAILURE: Build failed with an exception.", "UNKNOWN"),
    ("unknown_generic_signing_word", "Reusing signingConfig in build.gradle\nFailure: invalid DSL", "UNKNOWN"),
    ("sign_after_network_failure", "Could not resolve dependencies\nsigningConfig debug setup", "NETWORK"),
    ("kotlin_after_network_failure", "Could not GET dependency\nCompilation error", "KOTLIN"),
    ("java_after_signing_setup", "KeyStore configured\nMain.java:12: error: bad statement", "JAVA"),
    ("sign_with_sdk_setup", "SDK location configured\nFailed to sign APK", "SIGNING"),
]

class ClassifierCases(unittest.TestCase):
    pass

def register_case(name, log, expected):
    def test(self):
        actual, label = diag.classify(log)
        self.assertEqual(actual, expected)
        self.assertEqual(label.startswith(diag.LABELS[expected]), True)
    test.__name__ = "test_" + name
    setattr(ClassifierCases, test.__name__, test)

for _name, _log, _expected in CASES:
    register_case(_name, _log, _expected)

class GuardCases(unittest.TestCase):
    def test_path_accepts_exact_entries(self):
        self.assertEqual(guard.allowed_paths("a/b.py\nc/d.yml\n"), {"a/b.py", "c/d.yml"})
    def test_path_rejects_empty_list(self):
        with self.assertRaises(ValueError): guard.allowed_paths("")
    def test_path_rejects_traversal(self):
        with self.assertRaises(ValueError): guard.allowed_paths("a/../secret")
    def test_path_rejects_absolute(self):
        with self.assertRaises(ValueError): guard.allowed_paths("/etc/passwd")
    def test_path_rejects_globs(self):
        with self.assertRaises(ValueError): guard.allowed_paths("app/**")
    def test_path_rejects_backslash(self):
        with self.assertRaises(ValueError): guard.allowed_paths("app\\secret")
    def test_path_rejects_dot_segment(self):
        with self.assertRaises(ValueError): guard.allowed_paths("a/./b")
    def test_diff_rejects_unexpected_code(self):
        self.assertEqual(guard.unexpected_paths({"app/music.kt","tools/a.py"}, {"tools/a.py"}), {"app/music.kt"})
    def test_diff_accepts_allowed_paths_only(self):
        self.assertEqual(guard.unexpected_paths({"tools/a.py"}, {"tools/a.py"}), set())
    def test_baseline_rejects_injection(self):
        with self.assertRaises(ValueError): guard.changed_files("HEAD; rm -rf /")
    def test_unknown_log_returns_no_secret_material(self):
        self.assertEqual(diag.classify("password=123\nSigning cache restored")[0], "UNKNOWN")
    def test_classifier_is_standalone_python(self):
        result = subprocess.run(
            [sys.executable, str(TOOL / "classify-gradle-failure.py"), "/nonexistent/log"],
            stdout=subprocess.PIPE, text=True, check=True,
        )
        self.assertIn("non classificato", result.stdout)
    def test_shell_uses_one_classifier(self):
        shell = (TOOL / "build-apk.sh").read_text()
        self.assertEqual(shell.count('python3 "$CLASSIFIER" "$LOG_FILE"'), 1)
    def test_shell_keeps_original_gradle_exit(self):
        shell = (TOOL / "build-apk.sh").read_text()
        self.assertIn('exit "$BUILD_RC"', shell)
    def test_shell_preserves_gradle_task(self):
        shell = (TOOL / "build-apk.sh").read_text()
        self.assertIn('./gradlew "$TASK" --console=plain --warning-mode summary', shell)
    def test_shell_does_not_use_legacy_final_signing_override(self):
        shell = (TOOL / "build-apk.sh").read_text()
        self.assertNotIn("grep -Eqi 'Keystore|signing|storeFile|validateSigning'", shell)
    def test_shared_workflow_does_not_compile_apk(self):
        flow = (ROOT / ".github/workflows/motorlab-musiclab-shared-validation.yml").read_text()
        self.assertIn("workflow_call:", flow)
        self.assertNotIn("./gradlew", flow)
        self.assertIn("contents: read", flow)
    def test_driver_uses_shared_workflow_once(self):
        flow = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertEqual(flow.count("uses: ./.github/workflows/motorlab-musiclab-shared-validation.yml"), 1)
    def test_player_is_guarded_by_explicit_blob_hash(self):
        flow = (ROOT / ".github/workflows/motorlab-musiclab-shared-validation.yml").read_text()
        self.assertIn("03138a7b0d6771e4c7dde0ecc9ab11a8046990ff", flow)
    def test_workflow_not_deployed_to_production(self):
        flow = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertNotIn("deploy", flow.lower())
        self.assertNotIn("secrets.", flow)
    def test_guard_checks_deletions_too(self):
        script = (TOOL / "verify-changed-files.py").read_text()
        self.assertIn('"diff", "--name-only", "-z"', script)

if __name__ == "__main__":
    unittest.main()
