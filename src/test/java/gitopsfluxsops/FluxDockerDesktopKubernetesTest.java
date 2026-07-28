package gitopsfluxsops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FluxDockerDesktopKubernetesTest {

    private static final String APP_NAME = "spring-boot-k8s-gitops-flux-sops";
    private static final String FLUX_NAMESPACE = "flux-system";
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.root", ".")).toAbsolutePath().normalize();

    @Test
    void fluxDecryptsSopsSecretsAndDeploysDevAndProdApplications() throws Exception {
        requireWorkshopSopsMaterial("dev");
        requireWorkshopSopsMaterial("prod");
        requireFluxSopsAgeSecret("dev");
        requireFluxSopsAgeSecret("prod");

        requireCommand("docker");
        requireCommand("kubectl");
        requireCommand("flux");

        assertDockerDesktopContext();
        ensureFluxInstalled();

        boolean success = false;
        try {
            cleanupOwnedResources();
            applyDockerDesktopClusterGitops();

            waitForFluxSource();
            verifyEnvironment("dev", "dev-workshop-token1", "dev-workshop-token2");
            verifyEnvironment("prod", "prod-workshop-token1", "prod-workshop-token2");

            success = true;
        } finally {
            if (success) {
                cleanupOwnedResources();
            }
        }
    }

    private static void requireWorkshopSopsMaterial(String environment) throws IOException {
        Path encryptedSecret = PROJECT_ROOT.resolve("gitops/overlays/%s/secret.enc.yaml".formatted(environment));
        String content = Files.readString(encryptedSecret);

        assertThat(content)
                .as("%s must be a real SOPS-encrypted Secret before running ./gradlew test".formatted(encryptedSecret))
                .contains("ENC[")
                .contains("sops:")
                .doesNotContain("workshop-token1")
                .doesNotContain("workshop-token2");
    }

    private static void requireFluxSopsAgeSecret(String environment) throws IOException {
        Path secret = PROJECT_ROOT.resolve("gitops/clusters/%s/flux-sops-age-key-bootstrap.yaml".formatted(environment));
        String content = Files.readString(secret);

        assertThat(content)
                .as("%s must contain the workshop age private key".formatted(secret))
                .contains("AGE-SECRET-KEY-")
                .doesNotContain("<replace-with-generated-age-private-key>");
    }

    private static void requireCommand(String command) {
        CommandResult result = runAllowingFailure(Duration.ofSeconds(15), "sh", "-c", "command -v " + command);
        assertThat(result.exitCode())
                .as("Required command '%s' must be available on PATH".formatted(command))
                .isZero();
    }

    private static void assertDockerDesktopContext() {
        CommandResult result = run(Duration.ofSeconds(15), "kubectl", "config", "current-context");

        assertThat(result.stdout().trim())
                .as("./gradlew test deploys to the local Docker Desktop Kubernetes cluster")
                .isEqualTo("docker-desktop");
    }

    private static void ensureFluxInstalled() {
        CommandResult namespace = runAllowingFailure(Duration.ofSeconds(15), "kubectl", "get", "namespace", FLUX_NAMESPACE);
        if (namespace.exitCode() != 0) {
            run(Duration.ofMinutes(3), "flux", "install");
        }

        run(Duration.ofMinutes(2), "kubectl", "-n", FLUX_NAMESPACE, "rollout", "status", "deployment/source-controller", "--timeout=120s");
        run(Duration.ofMinutes(2), "kubectl", "-n", FLUX_NAMESPACE, "rollout", "status", "deployment/kustomize-controller", "--timeout=120s");
    }

    private static void cleanupOwnedResources() {
        runAllowingFailure(Duration.ofMinutes(2),
                "kubectl", "-n", FLUX_NAMESPACE, "delete",
                "kustomization.kustomize.toolkit.fluxcd.io",
                APP_NAME + "-dev",
                APP_NAME + "-prod",
                "--ignore-not-found=true");

        runAllowingFailure(Duration.ofMinutes(1),
                "kubectl", "-n", FLUX_NAMESPACE, "delete",
                "gitrepository.source.toolkit.fluxcd.io",
                APP_NAME + "-dev",
                APP_NAME + "-prod",
                "--ignore-not-found=true");

        runAllowingFailure(Duration.ofMinutes(2),
                "kubectl", "delete", "namespace",
                APP_NAME + "-dev",
                APP_NAME + "-prod",
                "--ignore-not-found=true");
    }

    private static void applyDockerDesktopClusterGitops() {
        Path devClusterPath = PROJECT_ROOT.resolve("gitops/clusters/dev");
        Path prodClusterPath = PROJECT_ROOT.resolve("gitops/clusters/prod");

        run(Duration.ofSeconds(30), "kubectl", "apply", "-k", devClusterPath.toString());
        run(Duration.ofSeconds(30), "kubectl", "apply", "-k", prodClusterPath.toString());
    }

    private static void waitForFluxSource() {
        run(Duration.ofMinutes(4),
                "kubectl", "-n", FLUX_NAMESPACE, "wait",
                "gitrepository.source.toolkit.fluxcd.io/" + APP_NAME + "-dev",
                "--for=condition=Ready",
                "--timeout=240s");

        run(Duration.ofMinutes(4),
                "kubectl", "-n", FLUX_NAMESPACE, "wait",
                "gitrepository.source.toolkit.fluxcd.io/" + APP_NAME + "-prod",
                "--for=condition=Ready",
                "--timeout=240s");
    }

    private static void verifyEnvironment(String environment, String expectedToken1, String expectedToken2) {
        String namespace = APP_NAME + "-" + environment;
        String fluxKustomization = APP_NAME + "-" + environment;

        run(Duration.ofMinutes(4),
                "kubectl", "-n", FLUX_NAMESPACE, "wait",
                "kustomization.kustomize.toolkit.fluxcd.io/" + fluxKustomization,
                "--for=condition=Ready",
                "--timeout=240s");

        run(Duration.ofMinutes(3),
                "kubectl", "-n", namespace, "rollout", "status",
                "deployment/" + APP_NAME,
                "--timeout=180s");

        CommandResult logs = run(Duration.ofSeconds(30),
                "kubectl", "-n", namespace, "logs",
                "-l", "app.kubernetes.io/name=" + APP_NAME,
                "--tail=200",
                "--prefix");

        assertThat(logs.stdout())
                .contains("WORKSHOP_TOKEN_CHECK")
                .contains("activeProfiles=[" + environment + "]")
                .contains("demo.token1=" + expectedToken1)
                .contains("demo.token2=" + expectedToken2);
    }

    private static CommandResult run(Duration timeout, String... command) {
        CommandResult result = runAllowingFailure(timeout, command);
        assertThat(result.exitCode())
                .as("""
                        Command failed.
                        command: %s
                        stdout:
                        %s
                        stderr:
                        %s
                        """.formatted(String.join(" ", command), result.stdout(), result.stderr()))
                .isZero();
        return result;
    }

    private static CommandResult runAllowingFailure(Duration timeout, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(PROJECT_ROOT.toFile())
                    .redirectErrorStream(false)
                    .start();

            boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AssertionError("Command timed out after %s: %s".formatted(timeout, String.join(" ", command)));
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            return new CommandResult(127, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running command: " + String.join(" ", command), e);
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {

        CommandResult pipeTo(Duration timeout, String... command) {
            try {
                Process process = new ProcessBuilder(command)
                        .directory(PROJECT_ROOT.toFile())
                        .redirectErrorStream(false)
                        .start();

                process.getOutputStream().write(stdout.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();

                boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    throw new AssertionError("Command timed out after %s: %s".formatted(timeout, String.join(" ", command)));
                }

                String pipedStdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String pipedStderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                CommandResult result = new CommandResult(process.exitValue(), pipedStdout, pipedStderr);

                assertThat(result.exitCode())
                        .as("""
                                Piped command failed.
                                command: %s
                                stdin:
                                %s
                                stdout:
                                %s
                                stderr:
                                %s
                                """.formatted(String.join(" ", command), stdout, result.stdout(), result.stderr()))
                        .isZero();

                return result;
            } catch (IOException e) {
                throw new AssertionError("Failed to run piped command: " + String.join(" ", command), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while running piped command: " + String.join(" ", command), e);
            }
        }
    }
}
