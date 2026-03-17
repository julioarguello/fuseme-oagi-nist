package org.oagi.srt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@ComponentScan("org.oagi.srt")
@EnableJpaRepositories("org.oagi.srt.repository")
// (JAF), 20260317, Removed @EntityScan("org.oagi.srt.repository.entity"):
// causes duplicate entity scanning with Spring Boot 1.5.x auto-config
@SpringBootApplication(exclude = {
        WebMvcAutoConfiguration.class
})
public class ImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportApplication.class, args);
    }
}
