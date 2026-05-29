package com.sakura.spring.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sakura.spring.ai.agent.mapper")
public class SpringAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringAiAgentApplication.class, args);
	}
}
