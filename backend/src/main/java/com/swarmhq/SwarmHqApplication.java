package com.swarmhq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling: needed for MissionAssignmentService's periodic assignment pass.
@SpringBootApplication
@EnableScheduling
public class SwarmHqApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwarmHqApplication.class, args);
	}

}
