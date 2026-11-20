package dev.dineshrama.tracelens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(TraceProperties.class)
public class TraceLensApplication {
  public static void main(String[] args) { SpringApplication.run(TraceLensApplication.class, args); }
}
