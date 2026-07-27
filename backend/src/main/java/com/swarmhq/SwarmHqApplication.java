package com.swarmhq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling: MissionAssignmentService's periodic assignment pass (Sprint 10).
@SpringBootApplication
@EnableScheduling
public class SwarmHqApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwarmHqApplication.class, args);
	}

}
