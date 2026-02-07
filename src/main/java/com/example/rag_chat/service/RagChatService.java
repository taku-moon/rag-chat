package com.example.rag_chat.service;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

@Service
public class RagChatService {

	private final ChatClient chatClient;

	public RagChatService(ChatClient.Builder chatClientBuilder, Advisor[] advisors) {
		this.chatClient = chatClientBuilder.defaultOptions(ChatOptions.builder().temperature(0.0).build())
			.defaultAdvisors(advisors)
			.build();
	}

	public ChatResponse call(String conversationId, Prompt prompt, Optional<String> filterExpressionAsOpt) {
		return buildChatClientRequestSpec(conversationId, prompt, filterExpressionAsOpt).call().chatResponse();
	}

	public Flux<String> stream(String conversationId, Prompt prompt, Optional<String> filterExpressionAsOpt) {
		return buildChatClientRequestSpec(conversationId, prompt, filterExpressionAsOpt).stream().content();
	}

	private ChatClient.ChatClientRequestSpec buildChatClientRequestSpec(
		String conversationId, Prompt prompt, Optional<String> filterExpressionAsOpt
	) {
		ChatClient.ChatClientRequestSpec chatClientRequestSpec = chatClient.prompt(prompt)
			.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId));

		filterExpressionAsOpt.ifPresent(filterExpression -> chatClientRequestSpec.advisors(
			advisors -> advisors.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterExpression))
		);

		return chatClientRequestSpec;
	}
}
