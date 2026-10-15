# Architecture

Claude-Agent-Demo is a Spring Boot service that answers questions about uploaded PDFs. PDFs are chunked,
embedded with Amazon Titan and stored in PostgreSQL + pgvector; a chat agent built on the Anthropic Java SDK
calls Claude on Amazon Bedrock, which searches those chunks through a tool and answers with citations.

## System context

```mermaid
flowchart LR
    user([User / client app])

    subgraph app [Spring Boot app - Claude-Agent-Demo]
        docApi[DocumentController<br/>POST /api/documents]
        chatApi[ChatController<br/>POST /api/chat]
        ingest[PdfIngestionService]
        agent[ChatAgent]
        store[(VectorStore<br/>Spring AI pgvector)]
        emb[Titan EmbeddingModel<br/>Spring AI Bedrock]
        sdk[AnthropicClient<br/>Java SDK + BedrockBackend]
    end

    subgraph aws [AWS - Amazon Bedrock, bedrock-runtime]
        claude[Claude Haiku 4.5<br/>us.anthropic.claude-haiku-4-5-20251001-v1:0]
        titan[Titan Text Embeddings V2<br/>amazon.titan-embed-text-v2:0]
    end

    pg[(PostgreSQL 17 + pgvector<br/>table vector_store)]

    user -->|upload PDF| docApi --> ingest
    user -->|question| chatApi --> agent
    ingest -->|chunks| store
    agent -->|search_documents| store
    store --> emb -->|InvokeModel| titan
    store <-->|SQL / HNSW cosine| pg
    agent --> sdk -->|InvokeModel, SigV4| claude
```

## Components

| Component | Package / class | Responsibility |
| --- | --- | --- |
| Document API | `ingestion.DocumentController` | Accepts a multipart PDF upload, rejects non-PDF files with 400 |
| Ingestion | `ingestion.PdfIngestionService` | Reads the PDF page by page, splits pages into ~800-token chunks that overlap by 120 tokens, tags each chunk with `source` (file name) and `page_number`, replaces any earlier chunks for the same file, stores the new ones |
| Chat API | `chat.ChatController` | Accepts `{conversationId?, question}`, returns `{conversationId, answer, citations[]}` |
| Chat agent | `chat.ChatAgent` | Runs the Claude tool-use loop (max 8 turns), executes `search_documents`, returns results as `search_result` blocks with citations enabled, extracts citations from the final answer |
| Claude client | `config.ClaudeConfig` | Builds the `AnthropicClient` with `BedrockBackend` (bedrock-runtime) and the default AWS credential chain |
| Vector store | Spring AI `PgVectorStore` (auto-configured) | Embeds text through Titan on write and on query; similarity search over pgvector |
| Embedding model | Spring AI `BedrockTitanEmbeddingModel` (auto-configured) | Calls Titan Text Embeddings V2, 1024 dimensions |

## Data model

Spring AI creates the table on startup (`spring.ai.vectorstore.pgvector.initialize-schema=true`).

```mermaid
erDiagram
    VECTOR_STORE {
        uuid id PK
        text content "chunk text"
        json metadata "source, page_number, file_name, ..."
        vector_1024 embedding "Titan V2, HNSW index, cosine"
    }
```

## Key design decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Claude access | Anthropic Java SDK with `BedrockBackend` | Full Messages API (tools, citations, caching) while staying inside AWS |
| Retrieval style | Retrieval as a tool (`search_documents`) | Claude can search several times, rephrase, or split a multi-part question |
| Grounding | Results returned as `search_result` blocks with `citations.enabled=true` | Citations come back as structured data tied to source chunks, not text the model types |
| Embeddings | Titan Text Embeddings V2, 1024 dims | Runs on Bedrock in the same account; no extra vendor |
| Index | pgvector HNSW, cosine distance | Fast approximate search without a separate vector database |
| Chunking | Sliding-window token splitter (`OverlappingTokenTextSplitter`), ~800 tokens with 120-token overlap, one PDF page per source document; chunks end on a sentence boundary where possible | Overlap keeps text near a chunk boundary whole in at least one chunk; page-level splitting keeps page numbers for citations; chunk size fits well inside Titan's input limit |
| Re-ingestion | Delete chunks where `source` = file name, then insert | Uploading a changed PDF replaces it instead of duplicating it |
| Prompt caching | `cache_control` on the fixed system prompt | System prompt and tool list are identical on every request |
| Credentials | Default AWS credential chain (profile locally, IAM role in AWS) | No keys in configuration |

## Configuration

All settings are in `src/main/resources/application.properties` and can be overridden with environment variables
(for example `APP_CLAUDE_MODEL`).

| Property | Default | Purpose |
| --- | --- | --- |
| `app.aws.region` | `${AWS_REGION:us-east-1}` | Region for both Claude and Titan |
| `app.claude.model` | `us.anthropic.claude-haiku-4-5-20251001-v1:0` | Claude inference profile ID (Haiku 4.5: lowest-cost model available to the account) |
| `app.claude.max-tokens` | `16000` | Max output tokens per Claude call |
| `app.claude.effort` | empty | Effort level (`low` to `max`); leave empty for Haiku 4.5, which doesn't support it |
| `app.ingestion.chunk-size-tokens` | `800` | Target chunk size |
| `app.ingestion.chunk-overlap-tokens` | `120` | Tokens each chunk repeats from the end of the previous chunk on the same page; `0` turns overlap off, must be less than the chunk size |
| `app.retrieval.top-k` | `6` | Chunks returned per search |
| `app.retrieval.similarity-threshold` | `0.3` | Minimum similarity for a chunk to be returned |

## Runtime environment

```mermaid
flowchart LR
    subgraph dev [Developer machine]
        boot[./gradlew bootRun]
        compose[Spring Boot Docker Compose]
        pgc[(pgvector/pgvector:pg17<br/>container, port 5432)]
        creds[~/.aws profile<br/>AWS_PROFILE, AWS_REGION]
    end
    bedrock[Amazon Bedrock<br/>bedrock-runtime, us-east-1]

    boot --> compose -->|starts, wires datasource| pgc
    boot -->|SigV4 using| creds
    boot -->|HTTPS| bedrock
```

## Current limitations

- Conversation history is kept in memory (`ConcurrentHashMap`), so it is lost on restart and not shared across instances.
- No authentication or authorization on the REST endpoints.
- A Claude refusal (`stop_reason: refusal`) returns a fixed message; there is no fallback model.
- Retrieval is vector-only; keyword (full-text) search and reranking are not implemented yet.
- Large PDFs are ingested synchronously within the upload request.
