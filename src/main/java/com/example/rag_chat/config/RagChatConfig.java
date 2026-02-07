package com.example.rag_chat.config;

import java.util.Optional;
import java.util.Scanner;
import java.util.function.Predicate;

import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.rag_chat.service.RagChatService;

import ch.qos.logback.classic.LoggerContext;

@Configuration
public class RagChatConfig {

	@Bean
	public SimpleLoggerAdvisor simpleLoggerAdvisor() {
		return SimpleLoggerAdvisor.builder().build();
	}

	@Bean
	public ChatMemory chatMemory() {
		return MessageWindowChatMemory.builder()
			.maxMessages(10)
			.build();
	}

	@Bean
	public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
		return MessageChatMemoryAdvisor.builder(chatMemory)
			.build();
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.cli", name = "enabled", havingValue = "true")
	public CommandLineRunner cli(
		@Value("${spring.application.name}") String applicationName,
		RagChatService ragChatService,
		@Value("${app.cli.filter-expression:}") String filterExpression
	) {
		return args -> {
			LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();
			context.getLogger("ROOT").detachAppender("CONSOLE");

			System.out.println("\n" + applicationName + " CLI bot");
			try (Scanner scanner = new Scanner(System.in)) {
				while (true) {
					System.out.print("\nUSER: ");
					String userMessage = scanner.nextLine();
					ragChatService.stream("cli", Prompt.builder().content(userMessage).build(),
							Optional.of(filterExpression).filter(Predicate.not(String::isBlank)))
						.doFirst(() -> System.out.print("\nASSISTANT: "))
						.doOnNext(System.out::print)
						.doOnComplete(System.out::println)
						.blockLast();
				}
			}
		};
	}
}
