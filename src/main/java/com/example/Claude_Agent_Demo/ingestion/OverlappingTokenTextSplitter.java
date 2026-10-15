package com.example.Claude_Agent_Demo.ingestion;

import java.util.ArrayList;
import java.util.List;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * Token splitter with a sliding window: each chunk repeats the last {@code overlapTokens} tokens of the
 * previous one, so text near a chunk boundary is fully contained in at least one chunk. Like Spring AI's
 * {@code TokenTextSplitter}, a chunk is cut back to its last sentence-ending punctuation when that keeps
 * at least {@code minChunkSizeChars} characters.
 */
public class OverlappingTokenTextSplitter extends TextSplitter {

	private static final List<Character> PUNCTUATION_MARKS = List.of('.', '?', '!', '\n');

	private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	private final int chunkSize;

	private final int overlapTokens;

	private final int minChunkSizeChars;

	public OverlappingTokenTextSplitter(int chunkSize, int overlapTokens, int minChunkSizeChars) {
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("chunkSize must be positive, was " + chunkSize);
		}
		if (overlapTokens < 0 || overlapTokens >= chunkSize) {
			throw new IllegalArgumentException(
					"overlapTokens must be >= 0 and < chunkSize (" + chunkSize + "), was " + overlapTokens);
		}
		this.chunkSize = chunkSize;
		this.overlapTokens = overlapTokens;
		this.minChunkSizeChars = minChunkSizeChars;
	}

	@Override
	protected List<String> splitText(String text) {
		List<String> chunks = new ArrayList<>();
		if (text.isBlank()) {
			return chunks;
		}

		IntArrayList tokens = this.encoding.encode(text);
		int start = 0;
		while (start < tokens.size()) {
			int end = Math.min(start + this.chunkSize, tokens.size());
			String chunkText = decode(tokens, start, end);
			int consumed = end - start;

			// Not the last window: end the chunk on a sentence boundary when there is one far enough in.
			if (end < tokens.size()) {
				int lastPunctuation = lastPunctuationIndex(chunkText);
				if (lastPunctuation > this.minChunkSizeChars) {
					chunkText = chunkText.substring(0, lastPunctuation + 1);
					consumed = Math.min(this.encoding.countTokens(chunkText), end - start);
				}
			}

			if (!chunkText.isBlank()) {
				chunks.add(chunkText.trim());
			}
			if (end == tokens.size()) {
				break;
			}
			// Step back by the overlap; skip it if the chunk was cut so short that it would stall.
			start += (consumed > this.overlapTokens) ? consumed - this.overlapTokens : consumed;
		}
		return chunks;
	}

	private String decode(IntArrayList tokens, int from, int to) {
		IntArrayList slice = new IntArrayList(to - from);
		for (int i = from; i < to; i++) {
			slice.add(tokens.get(i));
		}
		return this.encoding.decode(slice);
	}

	private static int lastPunctuationIndex(String text) {
		int last = -1;
		for (char mark : PUNCTUATION_MARKS) {
			last = Math.max(last, text.lastIndexOf(mark));
		}
		return last;
	}

}
