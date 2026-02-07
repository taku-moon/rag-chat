package com.example.rag_chat.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.rag_chat.service.RagChatService;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/rag")
class RagChatController {

	private final RagChatService ragChatService;

	public RagChatController(RagChatService ragChatService) {
		this.ragChatService = ragChatService;
	}

	public record RagPromptBody(
		@NotEmpty String conversationId,
		@NotEmpty String userPrompt,
		@Nullable String systemPrompt,
		@Nullable DefaultChatOptions chatOptions,
		@Nullable String filterExpression
	) {
	}

	@PostMapping(value = "/call", produces = MediaType.APPLICATION_JSON_VALUE)
	ChatResponse call(@RequestBody @Valid RagPromptBody ragPromptBody) {
		Prompt.Builder promptBuilder = getPromptBuilder(ragPromptBody);
		return this.ragChatService.call(
			ragPromptBody.conversationId,
			promptBuilder.build(),
			Optional.ofNullable(ragPromptBody.filterExpression)
		);
	}

	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Flux<String> stream(@RequestBody @Valid RagPromptBody ragPromptBody) {
		Prompt.Builder promptBuilder = getPromptBuilder(ragPromptBody);
		return this.ragChatService.stream(
			ragPromptBody.conversationId,
			promptBuilder.build(),
			Optional.ofNullable(ragPromptBody.filterExpression)
		);
	}

	private static Prompt.Builder getPromptBuilder(RagPromptBody ragPromptBody) {
		List<Message> messages = new ArrayList<>();

		messages.add(UserMessage.builder().text(ragPromptBody.userPrompt).build());

		Optional.ofNullable(ragPromptBody.systemPrompt)
			.filter(Predicate.not(String::isBlank))
			.map(systemPrompt -> SystemMessage.builder().text(systemPrompt).build())
			.ifPresent(messages::add);

		Prompt.Builder promptBuilder = Prompt.builder().messages(messages);

		Optional.ofNullable(ragPromptBody.chatOptions).ifPresent(promptBuilder::chatOptions);

		return promptBuilder;
	}
}
