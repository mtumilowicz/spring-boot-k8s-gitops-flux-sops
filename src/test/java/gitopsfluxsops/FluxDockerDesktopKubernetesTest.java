package gitopsfluxsops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class FluxDockerDesktopKubernetesTest {

    private static final String APP = "spring-boot-k8s-gitops-flux-sops-workshop";
    private static final Path ROOT = Path.of(System.getProperty("project.root", ".")).toAbsolutePath();

    @Test
    void fluxDeploysDevAndProd() throws Exception {
        run("kubectl", "apply", "-k", ROOT.resolve("gitops/clusters/dev").toString());
        run("kubectl", "apply", "-k", ROOT.resolve("gitops/clusters/prod").toString());

        verify("dev", "dev-workshop-token1", "dev-workshop-token2");
        verify("prod", "prod-workshop-token1", "prod-workshop-token2");
    }

    private static void verify(String environment, String token1, String token2) throws Exception {
        String namespace = APP + "-" + environment;

        run("flux", "reconcile", "kustomization", APP + "-" + environment, "-n", "flux-system", "--with-source");
        run("kubectl", "-n", namespace, "rollout", "restart", "deployment/" + APP);
        run("kubectl", "-n", namespace, "rollout", "status", "deployment/" + APP, "--timeout=180s");

        String logs = run("kubectl", "-n", namespace, "logs", "deployment/" + APP, "--tail=200");

        assertThat(logs)
                .contains("activeProfiles=[" + environment + "]")
                .contains("demo.token1=" + token1)
                .contains("demo.token2=" + token2);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        System.out.println("+ " + String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (!output.isBlank()) {
            System.out.println(output.strip());
        }

        assertThat(exitCode)
                .as("Command failed: %s%n%s", String.join(" ", command), output)
                .isZero();

        return output;
    }

    @AfterAll
    static void cleanup() throws IOException, InterruptedException {
        run("kubectl", "-n", "flux-system", "delete", "kustomization", APP + "-dev", APP + "-prod", "--ignore-not-found");
        run("kubectl", "-n", "flux-system", "delete", "gitrepository", APP + "-dev", APP + "-prod", "--ignore-not-found");
        run("kubectl", "delete", "namespace", APP + "-dev", APP + "-prod", "--ignore-not-found");
    }
}
