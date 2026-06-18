package com.sakura.spring.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.sakura.spring.ai.agent.mapper")
@EnableAsync
@EnableScheduling
public class SpringAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiAgentApplication.class, args);
	}
}
