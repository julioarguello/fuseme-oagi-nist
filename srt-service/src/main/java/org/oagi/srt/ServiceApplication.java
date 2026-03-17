package org.oagi.srt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration;
// (JAF), 20260317, EntityScan moved from org.springframework.boot.orm.jpa in Spring Boot 1.4.x
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@ComponentScan("org.oagi.srt")
@EnableJpaRepositories("org.oagi.srt.repository")
@EntityScan("org.oagi.srt.repository.entity")
@SpringBootApplication(exclude = {
        WebMvcAutoConfiguration.class
})
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }
}
