package com.example.Claude_Agent_Demo.ingestion;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OverlappingTokenTextSplitterTests {

	private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	private static String sentences(int count) {
		return IntStream.rangeClosed(1, count)
			.mapToObj(i -> "Sentence number " + i + " talks about account fees and overdraft limits.")
			.collect(Collectors.joining(" "));
	}

	@Test
	void consecutiveChunksShareText() {
		var splitter = new OverlappingTokenTextSplitter(100, 30, 50);

		List<String> chunks = splitter.splitText(sentences(40));

		assertThat(chunks).hasSizeGreaterThan(2);
		for (int i = 1; i < chunks.size(); i++) {
			String previous = chunks.get(i - 1);
			String head = chunks.get(i).substring(0, 40);
			assertThat(previous).as("chunk %d should start inside chunk %d", i, i - 1).contains(head.trim());
		}
	}

	@Test
	void everySentenceIsWhollyInSomeChunk() {
		var splitter = new OverlappingTokenTextSplitter(100, 30, 50);
		String text = sentences(40);

		List<String> chunks = splitter.splitText(text);

		for (int i = 1; i <= 40; i++) {
			String sentence = "Sentence number " + i + " talks about account fees and overdraft limits.";
			assertThat(chunks).as("sentence %d", i).anySatisfy(chunk -> assertThat(chunk).contains(sentence));
		}
	}

	@Test
	void chunksStayWithinChunkSize() {
		var splitter = new OverlappingTokenTextSplitter(100, 30, 50);

		List<String> chunks = splitter.splitText(sentences(40));

		assertThat(chunks).allSatisfy(chunk -> assertThat(this.encoding.countTokens(chunk)).isLessThanOrEqualTo(100));
	}

	@Test
	void zeroOverlapProducesNoRepeatedText() {
		var splitter = new OverlappingTokenTextSplitter(100, 0, 50);

		List<String> chunks = splitter.splitText(sentences(40));

		for (int i = 1; i <= 40; i++) {
			String sentence = "Sentence number " + i + " talks";
			assertThat(chunks.stream().filter(chunk -> chunk.contains(sentence))).as("sentence %d", i).hasSize(1);
		}
	}

	@Test
	void shortTextIsOneChunk() {
		var splitter = new OverlappingTokenTextSplitter(800, 120, 200);

		assertThat(splitter.splitText("A short page.")).containsExactly("A short page.");
		assertThat(splitter.splitText("   ")).isEmpty();
	}

	@Test
	void chunksKeepPageMetadata() {
		var splitter = new OverlappingTokenTextSplitter(100, 30, 50);
		var page = new Document(sentences(40), Map.of("page_number", 7, "file_name", "guide.pdf"));

		List<Document> chunks = splitter.apply(List.of(page));

		assertThat(chunks).hasSizeGreaterThan(2)
			.allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("page_number", 7));
	}

	@Test
	void rejectsOverlapNotSmallerThanChunkSize() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OverlappingTokenTextSplitter(100, 100, 50));
		assertThatIllegalArgumentException().isThrownBy(() -> new OverlappingTokenTextSplitter(100, -1, 50));
	}

}
