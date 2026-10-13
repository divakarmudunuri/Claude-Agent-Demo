# Testing

How to test the app end to end: upload a PDF, then ask the chat agent about it. Run all commands from the project
root.

The sample PDF [samples/everyday-checking-guide.pdf](../samples/everyday-checking-guide.pdf) is a fictional bank
account guide with specific fees and rules, so you can check the answers against it.

## 1. Check AWS access

1. Your AWS account needs a valid payment method (Billing and Cost Management → Payment preferences).
2. The Anthropic use-case form must be submitted. See the Bedrock setup guide.
3. In the Bedrock console playground, Claude Haiku 4.5 should answer `hi`. If it doesn't, the app won't work either.

Confirm your terminal uses the right account and region:

```bash
aws sts get-caller-identity
```

```bash
export AWS_REGION=us-east-1
```

## 2. Start the app

Docker Desktop must be running. Spring Boot starts the Postgres/pgvector container from `compose.yaml`
automatically.

```bash
./gradlew bootRun
```

Wait for `Started ClaudeAgentDemoApplication`. Leave it running and use a second terminal for the next steps.

## 3. Upload the PDF

```bash
curl -F file=@samples/everyday-checking-guide.pdf localhost:8080/api/documents
```

Expected:

```json
{"source":"everyday-checking-guide.pdf","pages":1,"chunks":2}
```

Uploading the same file again replaces its chunks; it doesn't duplicate them.

Optional: check the stored chunks.

```bash
docker exec claude-agent-demo-pgvector-1 psql -U rag -d ragdb -c "select metadata->>'source' as source, left(content, 60) as preview from vector_store"
```

## 4. Ask questions

### Test A: question the PDF answers

```bash
curl -s -H 'Content-Type: application/json' -d '{"question":"How can I avoid the monthly fee, and what does it cost if I do not?"}' localhost:8080/api/chat | python3 -m json.tool
```

Expected:

- The answer gives a monthly fee of **$12**, waived with **$500+ in direct deposits**, a **$1,500 minimum daily
  balance**, or a primary account holder **aged 24 or under**.
- `citations` lists `everyday-checking-guide.pdf`.

Copy the `conversationId` from the response for Test B.

### Test B: follow-up in the same conversation

Replace `PASTE-ID` with the `conversationId` from Test A.

```bash
curl -s -H 'Content-Type: application/json' -d '{"conversationId":"PASTE-ID","question":"And if I overdraw by 40 dollars, will I be charged?"}' localhost:8080/api/chat | python3 -m json.tool
```

Expected: **no fee**, because overdrafts of $50 or less at the end of the business day aren't charged. Citations
point to the overdraft section.

### Test C: question the PDF doesn't answer

```bash
curl -s -H 'Content-Type: application/json' -d '{"question":"What is the interest rate on the savings account?"}' localhost:8080/api/chat | python3 -m json.tool
```

Expected: the agent says it couldn't find this in the uploaded documents, does not invent a rate, and returns no
citations.

### Test D: input validation

```bash
curl -s -o /dev/null -w "%{http_code}\n" -F file=@build.gradle localhost:8080/api/documents
```

Expected: `400`, because only PDFs are accepted.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H 'Content-Type: application/json' -d '{"question":""}' localhost:8080/api/chat
```

Expected: `400`, because the question is required.

## 5. Troubleshooting

The `bootRun` terminal logs each `search_documents` query Claude makes, and any errors.

| Error in the log | Cause | Fix |
| --- | --- | --- |
| `INVALID_PAYMENT_INSTRUMENT` | No valid payment method, so the AWS Marketplace subscription for the model fails | Add or fix the payment method in AWS Billing, then wait 5 minutes |
| `Model use case details have not been submitted` | Anthropic use-case form not submitted | Submit the form, then wait 15 minutes |
| `not available for this account` | The model's tokens-per-minute quota is 0 | Use Haiku 4.5, or request a quota increase in Service Quotas |
| `Unable to load credentials` | No AWS credentials visible to the app | Set `AWS_PROFILE` and `AWS_REGION` in the terminal running the app |
| Connection refused on port 5432 | Docker isn't running | Start Docker Desktop and rerun `bootRun` |

## 6. Test with your own PDF

```bash
curl -F file=@/path/to/your.pdf localhost:8080/api/documents
```

Then ask questions as in step 4.

Press `Ctrl+C` in the `bootRun` terminal to stop the app. The database container keeps your data between runs.
