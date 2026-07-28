package gitopsfluxsops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpringBootK8sGitopsFluxSopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootK8sGitopsFluxSopsApplication.class, args);
    }
}
