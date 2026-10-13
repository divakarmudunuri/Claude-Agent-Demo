# Sequence Diagrams

These diagrams show the two request flows: uploading a PDF and asking a question. See
[architecture.md](architecture.md) for the components involved.

## 1. PDF ingestion

`POST /api/documents` (multipart, field `file`)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant DC as DocumentController
    participant IS as PdfIngestionService
    participant R as PagePdfDocumentReader
    participant S as TokenTextSplitter
    participant VS as PgVectorStore
    participant T as Titan Embeddings V2<br/>(Bedrock)
    participant DB as PostgreSQL + pgvector

    User->>DC: POST /api/documents (file=guide.pdf)
    alt not a .pdf file
        DC-->>User: 400 Only PDF files are supported
    else PDF
        DC->>IS: ingest(resource, "guide.pdf")
        IS->>R: get()
        R-->>IS: one Document per page (page_number metadata)
        IS->>S: apply(pages)
        S-->>IS: chunks (~800 tokens each)
        IS->>IS: add metadata source = "guide.pdf"
        IS->>VS: delete("source == 'guide.pdf'")
        VS->>DB: DELETE existing chunks for this file
        IS->>VS: add(chunks)
        loop each chunk
            VS->>T: InvokeModel(inputText)
            T-->>VS: 1024-dim embedding
        end
        VS->>DB: INSERT content, metadata, embedding
        IS-->>DC: IngestionResult(source, pages, chunks)
        DC-->>User: 200 {"source":"guide.pdf","pages":2,"chunks":2}
    end
```

## 2. Asking a question (agent loop with tool use)

`POST /api/chat` with `{"conversationId": optional, "question": "..."}`

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant CC as ChatController
    participant A as ChatAgent
    participant C as Claude on Bedrock<br/>(bedrock-runtime)
    participant VS as PgVectorStore
    participant T as Titan Embeddings V2
    participant DB as PostgreSQL + pgvector

    User->>CC: POST /api/chat {question}
    alt question missing or blank
        CC-->>User: 400 question is required
    end
    CC->>A: chat(conversationId, question)
    A->>A: load or create history, append user message

    loop up to 8 turns
        A->>C: messages.create(system prompt [cached], search_documents tool, history)
        C-->>A: response (stop_reason)
        A->>A: append full assistant content to history

        alt stop_reason = tool_use
            loop each search_documents call in the response
                A->>VS: similaritySearch(query, topK=6, threshold=0.3)
                VS->>T: embed query
                T-->>VS: query embedding
                VS->>DB: HNSW cosine search
                DB-->>VS: matching chunks
                VS-->>A: Documents (text, source, page_number)
                A->>A: map to search_result blocks (citations enabled)
            end
            A->>A: append all tool_result blocks in one user message
        else stop_reason = refusal
            A-->>CC: "I can't help with that request."
        else end_turn / max_tokens
            A->>A: join text blocks, collect search_result citations
            A-->>CC: ChatAnswer(conversationId, answer, citations)
        end
    end

    CC-->>User: 200 {conversationId, answer, citations[]}
```

If the loop reaches 8 turns without a final answer, the agent returns "I couldn't finish answering within the
allowed number of steps."

## 3. Search results that don't answer the question

What happens when the documents don't contain the answer, e.g. "What is the savings interest rate?"

```mermaid
sequenceDiagram
    autonumber
    participant A as ChatAgent
    participant C as Claude
    participant VS as PgVectorStore

    A->>C: question + search_documents tool
    C-->>A: tool_use search_documents("savings account interest rate")
    A->>VS: similaritySearch(...)
    alt no chunk above the similarity threshold
        VS-->>A: []
        A->>C: tool_result "No matching passages found in the uploaded documents."
    else only unrelated chunks
        VS-->>A: chunks about fees and overdrafts
        A->>C: tool_result [search_result blocks]
    end
    opt Claude rephrases
        C-->>A: tool_use search_documents("interest rate APY")
        A->>VS: similaritySearch(...)
        VS-->>A: []
        A->>C: tool_result "No matching passages found..."
    end
    C-->>A: end_turn "I couldn't find that in the uploaded documents."
    Note over A,C: System prompt: answer only from search results.<br/>Say so when they don't contain the answer.
```

## 4. Follow-up question in the same conversation

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant A as ChatAgent
    participant H as In-memory history<br/>(conversationId → messages)
    participant C as Claude

    User->>A: question 1 (no conversationId)
    A->>H: create new id, store user message
    A->>C: history [q1]
    C-->>A: tool use, then answer 1
    A->>H: store assistant turns, tool results
    A-->>User: answer 1 + conversationId

    User->>A: question 2 + same conversationId
    A->>H: load history, append q2
    A->>C: history [q1, tool calls, a1, q2]
    Note over A,C: Claude can resolve "And if I overdraw by $40?"<br/>using earlier turns, and searches again if needed
    C-->>A: answer 2
    A-->>User: answer 2 + citations
```

## 5. Error paths

| Where | Error | Result returned to the caller |
| --- | --- | --- |
| Upload | File name does not end in `.pdf` | 400 `Only PDF files are supported` |
| Chat | `question` missing or blank | 400 `question is required` |
| `search_documents` | Unknown tool name, or the search throws | `tool_result` with `is_error: true`; Claude can retry or answer without it |
| Claude call | Bedrock 403 "not available for this account" / 404 "use case details have not been submitted" | 500 (exception propagates); see the troubleshooting section of the Bedrock setup guide |
| Claude call | Throttling / 5xx | SDK retries twice, then 500 |
| Claude response | `stop_reason: refusal` | 200 with "I can't help with that request." |
