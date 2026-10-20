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


class RealUabShellIntegration(unittest.TestCase):
    """Run the real build-apk.sh with an isolated fake JDK/SDK/Gradle, zero APK build."""

    @staticmethod
    def fake_build(log, failcode=42, include_classifier=True):
        import os
        import shutil
        import tempfile
        with tempfile.TemporaryDirectory(prefix="uab-lab-test-") as root:
            project = pathlib.Path(root)
            sdk = project / "fake-sdk"
            sdk.mkdir()
            binpath = project / "fake-bin"
            binpath.mkdir()
            fake_java = binpath / "java"
            fake_java.write_text('#!/bin/sh\nprintf \'openjdk version "21.0.6"\\n\' >&2\n')
            fake_java.chmod(0o755)
            gradle = project / "gradlew"
            gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$UAB_TEST_LOG"\nexit "$UAB_TEST_RC"\n')
            gradle.chmod(0o755)
            debug_key = project / "debug.keystore"
            debug_key.write_text("FAKE ISOLATED TEST ONLY")
            if include_classifier:
                tool = project / "tools/universal-apk-builder"
                tool.mkdir(parents=True)
                shutil.copyfile(TOOL / "classify-gradle-failure.py", tool / "classify-gradle-failure.py")
            env = os.environ.copy()
            env.update({
                "PATH": str(binpath) + os.pathsep + env["PATH"],
                "ANDROID_SDK_ROOT": str(sdk),
                "ANDROID_HOME": str(sdk),
                "UAB_TASK": ":app:assembleDebug",
                "UAB_TEST_LOG": log,
                "UAB_TEST_RC": str(failcode),
                "UAB_DEBUG_KEYSTORE": str(debug_key),
                "UAB_PARALLEL_INSTALL": "off",
                "UAB_OUTPUT_DIR": str(project / "dist"),
                "UAB_CONFIG_FILE": str(project / "nonexistent.env"),
            })
            return subprocess.run(["bash", str(TOOL / "build-apk.sh"), str(project)],
                                  text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, env=env,
                                  timeout=15)

SHELL_CASES = [
    ("real_kotlin_with_signing_setup", "Preparing signingConfig release\nKeystore restored\n"
     "e: file:///tmp/Library.kt:77:11 Unresolved reference 'artist'", "errore di compilazione Kotlin (Library.kt:77)"),
    ("real_java_error", "Signing setup\nMain.java:12: error: bad statement", "errore di compilazione Java (Main.java:12)"),
    ("real_signing_failure", "Keystore was tampered with, or password incorrect", "problema reale di firma/keystore"),
    ("real_test_failure", "signingConfig ready\nThere were failing tests", "test automatici falliti"),
    ("real_sdk_failure", "SDK location not found", "Android SDK non configurato"),
    ("real_network_failure", "Could not resolve dependency artifacts", "dipendenza o rete non disponibile"),
    ("real_unknown_has_signing_word", "signingConfig debug initialized\nBUILD FAILED", "errore Gradle non classificato"),
    ("real_resources_failure", "Android resource linking failed", "errore delle risorse Android"),
    ("real_out_of_space", "No space left on device", "risorse del runner insufficienti"),
]

def register_shell_case(name, fake_log, expected):
    def test(self):
        proc = RealUabShellIntegration.fake_build(fake_log)
        self.assertEqual(proc.returncode, 42, msg=proc.stdout[-1500:])
        self.assertIn("[UAB][FAILED] " + expected, proc.stdout)
        self.assertIn("[UAB][LOG]", proc.stdout)
        self.assertNotIn("[UAB] BUILD OK", proc.stdout)
    test.__name__ = "test_" + name
    setattr(RealUabShellIntegration, test.__name__, test)

for _name, _log, _expected in SHELL_CASES:
    register_shell_case(_name, _log, _expected)

class RealUabFailureModes(unittest.TestCase):
    def test_preserves_custom_gradle_exit_code(self):
        proc = RealUabShellIntegration.fake_build("Compilation error", failcode=23)
        self.assertEqual(proc.returncode, 23, msg=proc.stdout[-1000:])
    def test_missing_classifier_falls_back_and_preserves_failure(self):
        proc = RealUabShellIntegration.fake_build("Compilation error", failcode=53,
                                                   include_classifier=False)
        self.assertEqual(proc.returncode, 53)
        self.assertIn("[UAB][FAILED] errore Gradle non classificato", proc.stdout)
    def test_bash_shell_syntax(self):
        proc = subprocess.run(["bash", "-n", str(TOOL / "build-apk.sh")],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
    def test_existing_family_signing_code_unchanged(self):
        code = (TOOL / "build-apk.sh").read_text()
        self.assertIn('setup_family_signing\nensure_debug_keystore', code)
        self.assertIn('METROLIST_DEBUG_KEYSTORE_PATH', code)
    def test_existing_zip_integrity_code_unchanged(self):
        code = (TOOL / "build-apk.sh").read_text()
        self.assertIn('unzip -tq "$OUTPUT_DIR/', code)


class ReusableApkBuildContract(unittest.TestCase):
    def test_lab_caller_calls_reusable_build_once(self):
        caller = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertEqual(caller.count("uses: ./.github/workflows/motorlab-musiclab-reusable-apk-build.yml"), 1)
        self.assertIn("needs: regression", caller)
    def test_build_opt_in_and_not_on_every_push(self):
        caller = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertIn("default: false", caller)
        self.assertIn("[MOTORLAB_BUILD_APK_LAB_ONCE]", caller)
        self.assertIn("inputs.build_apk == true", caller)
    def test_build_pins_sha(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertIn("ref: $" + "{{ inputs.source_sha }}", wf)
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SOURCE_SHA"', wf)
        self.assertIn("persist-credentials: false", wf)
    def test_original_family_and_signature_check_preserved(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertIn("it.verlezza.musiclab.turbo01", wf)
        self.assertIn("versionCode='1027'", wf)
        self.assertIn("9A2F67CFB3C199836813ADDBF7BDFB0FA65EDC765FFACE4B6DF9490BB0962749", wf)
        self.assertIn('unzip -tq "$ZIP"', wf)
    def test_build_keeps_original_player_hash(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertIn("03138a7b0d6771e4c7dde0ecc9ab11a8046990ff", wf)
    def test_one_apk_compilation_and_no_unrelated_unit_suite(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertEqual(wf.count("bash tools/universal-apk-builder/build-apk.sh ."), 1)
        self.assertNotIn(":app:testFossDebugUnitTest", wf)
    def test_callable_build_cannot_run_itself_on_push(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertIn("workflow_call:", wf)
        self.assertNotIn("workflow_dispatch:", wf)
        self.assertNotIn("  push:", wf)
    def test_build_permissions_read_only(self):
        wf = (ROOT / ".github/workflows/motorlab-musiclab-reusable-apk-build.yml").read_text()
        self.assertIn("contents: read", wf)
        self.assertNotIn("contents: write", wf)
        self.assertNotIn("actions: write", wf)
    def test_legacy_lab27_workflow_still_exists(self):
        wf = (ROOT / ".github/workflows/turbo-lab27-video-first-batch-refill-apk.yml").read_text()
        self.assertIn("uab-build/musiclab-turbo-lab27-video-first-batch-refill-family-02", wf)
        self.assertIn("bash tools/universal-apk-builder/build-apk.sh .", wf)


class LabBranchIsolationContract(unittest.TestCase):
    def test_apk_build_ref_locked_to_isolated_lab(self):
        caller = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertIn("github.ref == 'refs/heads/motorlab/musiclab-pipeline-stabilization-lab-20261020'", caller)
    def test_no_production_writes_or_deploy(self):
        caller = (ROOT / ".github/workflows/motorlab-musiclab-pipeline-lab.yml").read_text()
        self.assertNotIn("deploy", caller.lower())
        self.assertNotIn("contents: write", caller)
    def test_shared_validation_exposes_no_secrets(self):
        shared = (ROOT / ".github/workflows/motorlab-musiclab-shared-validation.yml").read_text()
        self.assertNotIn("secrets.", shared)
    def test_app_source_unchanged_gate_is_mandatory(self):
        shared = (ROOT / ".github/workflows/motorlab-musiclab-shared-validation.yml").read_text()
        self.assertIn("git diff --quiet", shared)
        self.assertIn("app/src/main app/build.gradle.kts gradle.properties uab-project.env", shared)

if __name__ == "__main__":
    unittest.main()
