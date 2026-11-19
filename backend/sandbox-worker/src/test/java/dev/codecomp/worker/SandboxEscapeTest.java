package dev.codecomp.worker;

import dev.codecomp.sandbox.DockerSandbox;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.SandboxConfig;
import dev.codecomp.contracts.SandboxConfig.Language;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;
import java.util.Objects;

import static dev.codecomp.contracts.RunResult.RunStatus.MEMORY_LIMIT;
import static dev.codecomp.contracts.RunResult.RunStatus.OK;
import static dev.codecomp.contracts.RunResult.RunStatus.OUTPUT_LIMIT;
import static dev.codecomp.contracts.RunResult.RunStatus.RUNTIME_ERROR;
import static dev.codecomp.contracts.RunResult.RunStatus.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("dockerAvailable")
class SandboxEscapeTest {

    static DockerSandbox sandbox;

    static boolean dockerAvailable() {
        try {
            return new ProcessBuilder("docker", "info").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String env(String key, String fallback) {
        return Objects.requireNonNullElse(System.getenv(key), fallback);
    }

    @BeforeAll
    static void setUp() {
        sandbox = new DockerSandbox(new SandboxConfig("docker", env("SANDBOX_RUNTIME", ""), 1.0, 256, 64, 64, 65536, 4, 5000, Map.of(
                "python", new Language(env("SANDBOX_PYTHON_IMAGE", "python:3.12-alpine"), "main.py", "exec python3 -I -B /tmp/main.py", 5000),
                "java", new Language(env("SANDBOX_JAVA_IMAGE", "eclipse-temurin:21-jdk-alpine"), "Main.java",
                        "exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xmx128m -Xss512k /tmp/Main.java", 10000))));
        sandbox.prepare();
    }

    @AfterAll
    static void tearDown() { sandbox.close(); }

    RunResult py(String code) { return sandbox.run("python", code); }

    @Test
    void runsPythonAndJava() {
        assertThat(py("print(40 + 2)").stdout()).isEqualTo("42\n");
        var java = sandbox.run("java", "public class Main { public static void main(String[] a) { System.out.println(\"hi\"); } }");
        assertThat(java.status()).isEqualTo(OK);
        assertThat(java.stdout()).isEqualTo("hi\n");
    }

    @Test
    void reportsRuntimeErrors() {
        var r = py("1/0");
        assertThat(r.status()).isEqualTo(RUNTIME_ERROR);
        assertThat(r.stderr()).contains("ZeroDivisionError");
    }

    @Test
    void infiniteLoopTimesOut() {
        assertThat(py("while True: pass").status()).isEqualTo(TIMEOUT);
    }

    @Test
    void forkBombIsCapped() {
        var r = py("import os\nwhile True:\n  try:\n    os.fork()\n  except OSError:\n    print('blocked'); break");
        assertThat(r.status()).isIn(OK, TIMEOUT, OUTPUT_LIMIT);
        assertThat(r.stdout()).contains("blocked");
    }

    @Test
    void memoryBombIsKilled() {
        assertThat(py("x = []\nwhile True: x.append(bytearray(10**7))").status()).isIn(MEMORY_LIMIT, RUNTIME_ERROR);
    }

    @Test
    void outputFloodIsCutOff() {
        var r = py("while True: print('x' * 1000)");
        assertThat(r.status()).isEqualTo(OUTPUT_LIMIT);
        assertThat(r.truncated()).isTrue();
        assertThat(r.durationMs()).isLessThan(5000);
    }

    @Test
    void hasNoNetwork() {
        var r = py("import socket\ns = socket.socket(); s.settimeout(2)\ns.connect(('1.1.1.1', 80)); print('CONNECTED')");
        assertThat(r.stdout()).doesNotContain("CONNECTED");
        assertThat(r.status()).isEqualTo(RUNTIME_ERROR);
    }

    @Test
    void cannotReachCloudMetadata() {
        var r = py("import urllib.request\nprint(urllib.request.urlopen('http://169.254.169.254/latest/meta-data/', timeout=2).read())");
        assertThat(r.status()).isEqualTo(RUNTIME_ERROR);
    }

    @Test
    void rootFilesystemIsReadOnly() {
        assertThat(py("open('/etc/pwned', 'w').write('x')").stderr()).containsAnyOf("Read-only file system", "Permission denied");
    }

    @Test
    void runsAsNobody() {
        assertThat(py("import os; print(os.getuid(), os.getgid())").stdout().strip()).isEqualTo("65534 65534");
    }

    @Test
    void tmpIsSizeLimited() {
        var r = py("f = open('/tmp/big', 'wb')\nfor _ in range(200): f.write(b'0' * (1 << 20))\nprint('wrote')");
        assertThat(r.stdout()).doesNotContain("wrote");
    }
}
