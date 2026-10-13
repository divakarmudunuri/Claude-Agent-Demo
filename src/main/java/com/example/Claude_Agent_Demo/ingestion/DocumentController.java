package com.example.Claude_Agent_Demo.ingestion;

import java.io.IOException;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private final PdfIngestionService ingestionService;

	public DocumentController(PdfIngestionService ingestionService) {
		this.ingestionService = ingestionService;
	}

	@PostMapping(consumes = "multipart/form-data")
	public PdfIngestionService.IngestionResult upload(@RequestParam("file") MultipartFile file) throws IOException {
		String name = file.getOriginalFilename();
		if (name == null || !name.toLowerCase().endsWith(".pdf")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
		}
		var resource = new ByteArrayResource(file.getBytes()) {
			@Override
			public String getFilename() {
				return name;
			}
		};
		return ingestionService.ingest(resource, name);
	}

}
