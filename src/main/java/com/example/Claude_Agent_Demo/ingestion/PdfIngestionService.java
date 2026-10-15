package com.example.Claude_Agent_Demo.ingestion;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Reads a PDF page by page, splits it into overlapping token-sized chunks, embeds each chunk with
 * Titan Text Embeddings V2 and stores it in pgvector.
 */
@Service
public class PdfIngestionService {

	private static final Logger log = LoggerFactory.getLogger(PdfIngestionService.class);

	private final VectorStore vectorStore;

	private final OverlappingTokenTextSplitter splitter;

	public PdfIngestionService(VectorStore vectorStore,
			@Value("${app.ingestion.chunk-size-tokens}") int chunkSizeTokens,
			@Value("${app.ingestion.chunk-overlap-tokens}") int chunkOverlapTokens) {
		this.vectorStore = vectorStore;
		this.splitter = new OverlappingTokenTextSplitter(chunkSizeTokens, chunkOverlapTokens, 200);
	}

	public IngestionResult ingest(Resource pdf, String sourceName) {
		var reader = new PagePdfDocumentReader(pdf, PdfDocumentReaderConfig.builder()
				.withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
						.withNumberOfTopTextLinesToDelete(0)
						.build())
				.withPagesPerDocument(1)
				.build());

		List<Document> pages = reader.get();
		List<Document> chunks = splitter.apply(pages);
		chunks.forEach(chunk -> chunk.getMetadata().put("source", sourceName));

		// Re-ingesting the same file replaces its previous chunks.
		vectorStore.delete("source == '" + sourceName.replace("'", "\\'") + "'");
		vectorStore.add(chunks);

		log.info("Ingested {}: {} pages, {} chunks", sourceName, pages.size(), chunks.size());
		return new IngestionResult(sourceName, pages.size(), chunks.size());
	}

	public record IngestionResult(String source, int pages, int chunks) {
	}

}
