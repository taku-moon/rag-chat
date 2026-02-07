package com.example.rag_chat.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.StringUtils;

public class LengthTextSplitter extends TextSplitter {

	private final int chunkSize;
	private final int chunkOverlap;

	public LengthTextSplitter(int chunkSize, int chunkOverlap) {
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("chunkSize must be positive.");
		}
		if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
			throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize.");
		}
		this.chunkSize = chunkSize;
		this.chunkOverlap = chunkOverlap;
	}

	@Override
	protected List<String> splitText(String text) {
		List<String> chunks = new ArrayList<>();

		if (!StringUtils.hasText(text)) {
			return chunks;
		}

		int textLength = text.length();
		if (textLength <= chunkOverlap) {
			chunks.add(text);
			return chunks;
		}

		int position = 0;
		while (position < textLength) {
			int end = Math.min(position + chunkSize, textLength);
			chunks.add(text.substring(position, end));
			int next = end - chunkOverlap;
			if (next <= position) {
				break;
			}
			position = next;
		}

		return chunks;
	}
}
