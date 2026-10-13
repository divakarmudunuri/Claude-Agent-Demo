package com.example.Claude_Agent_Demo.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final ChatAgent chatAgent;

	public ChatController(ChatAgent chatAgent) {
		this.chatAgent = chatAgent;
	}

	@PostMapping
	public ChatAgent.ChatAnswer chat(@RequestBody ChatRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
		}
		return chatAgent.chat(request.conversationId(), request.question());
	}

	public record ChatRequest(String conversationId, String question) {
	}

}
