"""Compile/run the real Kotlin engine + cadence using an existing Gradle compiler cache.

python tests/run_regression.py --cache <Gradle user home>/caches/modules-2/files-2.1
Requires JDK 17+ and cached Kotlin compiler 2.0.21; no downloads or Android runtime.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import os

parser = argparse.ArgumentParser()
parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
cache = args.cache

def jar(group, artifact, version="*"):
    matches = sorted((cache / group / artifact).glob(f"{version}/*/*.jar"))
    if not matches:
        raise SystemExit(f"Missing cached dependency: {group}:{artifact}:{version}")
    return matches[-1]

stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")
compiler_cp = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"),
    stdlib,
    jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
    jar("org.jetbrains.kotlin", "kotlin-reflect"),
    jar("org.jetbrains.intellij.deps", "trove4j"),
    jar("org.jetbrains", "annotations"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
]
source_dir = root / "app/src/main/java/com/closedloop/app"
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.name == "nt" and os.environ.get("JAVA_HOME") else "java"
with tempfile.TemporaryDirectory(prefix="closedloop-regression-") as tmp:
    result = Path(tmp) / "tests.jar"
    subprocess.run([
        java, "-cp", os.pathsep.join(map(str, compiler_cp)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", str(stdlib), "-d", str(result),
        str(source_dir / "Engine.kt"), str(source_dir / "MonitorCadence.kt"),
        str(root / "tests/MonitorRegression.kt"),
    ], check=True)
    subprocess.run([
        java, "-cp", os.pathsep.join(map(str, [result, stdlib])),
        "com.closedloop.app.MonitorRegressionKt",
    ], check=True)
