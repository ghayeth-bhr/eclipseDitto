package org.naruprof.twin.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * naruprof-eclipseDitto assembly. Component scanning is limited to the incoming REST adapter;
 * all services and vendor adapters are wired explicitly in {@link TwinCompositionRoot}.
 */
@SpringBootApplication(scanBasePackages = {"org.naruprof.twin.app", "org.naruprof.twin.ws.rest"})
@EntityScan(basePackages = "org.naruprof.twin.dal.jpa.entity")
@EnableJpaRepositories(basePackages = "org.naruprof.twin.dal.jpa.repo")
@EnableConfigurationProperties(TwinProperties.class)
public class TwinApplication {

    public static void main(String[] args) {
        SpringApplication.run(TwinApplication.class, args);
    }
}
