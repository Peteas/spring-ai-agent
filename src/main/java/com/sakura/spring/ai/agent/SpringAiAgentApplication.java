package com.sakura.spring.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.sakura.spring.ai.agent.mapper")
@EnableAsync
public class SpringAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiAgentApplication.class, args);
	}
}
