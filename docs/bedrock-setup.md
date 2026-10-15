# Bedrock setup

How to enable the two Amazon Bedrock models this app uses, from the AWS console. You do this once per AWS account.

| Model | Used for | ID in `application.properties` | One-time step |
| --- | --- | --- | --- |
| Claude Haiku 4.5 (Anthropic) | Chat agent | `us.anthropic.claude-haiku-4-5-20251001-v1:0` (US cross-Region inference profile) | Submit the Anthropic use-case form |
| Titan Text Embeddings V2 (Amazon) | Chunk and query embeddings | `amazon.titan-embed-text-v2:0` | None, it is enabled by default |

Both are called in the region set by `AWS_REGION` (default `us-east-1`).

> **Note:** Bedrock no longer has a **Model access** page where you request each model. Serverless models are
> enabled by default, and third-party models subscribe automatically through AWS Marketplace the first time they
> are invoked. Older tutorials that say "Model access → Manage model access" are out of date. Console labels
> change from time to time, so if a menu name below doesn't match, look for the closest equivalent.

## 1. Check the account

1. Sign in to the [AWS console](https://console.aws.amazon.com/) with a user that can manage billing, IAM and
   Bedrock (an administrator is easiest for this one-time setup).
2. In the region selector (top right), choose **US East (N. Virginia) us-east-1**, or the region you will put in
   `AWS_REGION`.
3. Open **Billing and Cost Management → Payment preferences** and confirm there is a valid, verified payment method.
   Anthropic models are billed through AWS Marketplace, and both the use-case form and the subscription fail without
   one. The account's billing address must also be in a
   [country Anthropic supports](https://www.anthropic.com/supported-countries).

## 2. Enable Claude Haiku 4.5

1. Open the [Amazon Bedrock console](https://console.aws.amazon.com/bedrock/).
2. In the left navigation, choose **Model catalog**.
3. Filter by provider **Anthropic** and choose **Claude Haiku 4.5**.
4. Choose **Open in playground**. The first time anyone in the account opens an Anthropic model, Bedrock asks for
   **use case details** (the First Time Use form). Fill it in:
   - **Company name** and **Company website URL**. Individual developers can use a GitHub profile or project URL.
   - **Industry**, and whether the intended users are internal or external.
   - **Use case description**, for example: "Internal demo that answers questions about uploaded PDF documents
     using retrieval-augmented generation."
5. Submit the form. Access is usually granted straight away. Sometimes the agreement stays pending for a while;
   AWS emails you when it is ready, and until then calls return `AccessDeniedException`.
6. In the playground, send `hi`. A reply means Claude works for your console user.

That first call also starts the AWS Marketplace subscription for the model, which can take up to 15 minutes to
finish. The form covers every Anthropic model in all commercial regions, so you don't fill it in again when you switch
models. If the account belongs to an AWS Organization, a submission from the management account (made through the
API) also covers its member accounts.

## 3. Check Titan Text Embeddings V2

Amazon's own models are enabled by default and need no form or Marketplace subscription.

1. In **Model catalog**, filter by provider **Amazon** and open **Titan Text Embeddings V2**.
2. Confirm it is listed as available in your region. Embedding models can't be tried in the chat playground. The
   first PDF upload to the app is the real test (step 7 of this guide), or you can run this from a terminal:

```bash
aws bedrock-runtime invoke-model --region us-east-1 --model-id amazon.titan-embed-text-v2:0 --cli-binary-format raw-in-base64-out --body '{"inputText":"hello"}' /dev/stdout
```

A JSON response with an `embedding` array means it works.

## 4. Give the app's identity permission

The app signs requests with the default AWS credential chain (`AWS_PROFILE`, environment variables or an IAM role).
That identity needs permission to call both models. If it is the same administrator you used above, you can skip
this step.

1. Open **IAM → Policies → Create policy** and switch to the **JSON** editor.
2. Paste the policy below and replace `<ACCOUNT_ID>` with your 12-digit account ID (top right menu in the console).

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ClaudeInferenceProfile",
      "Effect": "Allow",
      "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
      "Resource": "arn:aws:bedrock:us-east-1:<ACCOUNT_ID>:inference-profile/us.anthropic.claude-haiku-4-5-20251001-v1:0"
    },
    {
      "Sid": "ClaudeModelInRoutedRegions",
      "Effect": "Allow",
      "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
      "Resource": "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0",
      "Condition": {
        "StringEquals": {
          "bedrock:InferenceProfileArn": "arn:aws:bedrock:us-east-1:<ACCOUNT_ID>:inference-profile/us.anthropic.claude-haiku-4-5-20251001-v1:0"
        }
      }
    },
    {
      "Sid": "TitanEmbeddings",
      "Effect": "Allow",
      "Action": "bedrock:InvokeModel",
      "Resource": "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2:0"
    },
    {
      "Sid": "MarketplaceAutoSubscribe",
      "Effect": "Allow",
      "Action": ["aws-marketplace:Subscribe", "aws-marketplace:Unsubscribe", "aws-marketplace:ViewSubscriptions"],
      "Resource": "*",
      "Condition": { "StringEquals": { "aws:CalledViaLast": "bedrock.amazonaws.com" } }
    }
  ]
}
```

3. Name it, for example `ClaudeAgentDemoBedrock`, and choose **Create policy**.
4. Open **IAM → Users** (or **Roles**), select the identity the app runs as, and choose
   **Add permissions → Attach policies directly**. Pick `ClaudeAgentDemoBedrock` and save.

Notes on the policy:
- The `us.` inference profile can send a request to any US region, so the Claude model is allowed in all regions
  (`*`), but only when called through that profile.
- The Marketplace statement is only needed for the first invocation, which starts the subscription. Once the model
  has been enabled in the account (for example by your step 2 test in the console), you can remove it.
- If you use a different region, change `us-east-1` in the ARNs. Outside the US, use the matching geographic profile
  (`eu.`, `apac.`, `jp.`, `au.`, `in.`) and update `app.claude.model` to match.

## 5. Check quotas

A new account can have a quota of 0 for some models, which shows up as `ThrottlingException` or "not available for
this account" errors.

1. Open **Service Quotas → AWS services → Amazon Bedrock**.
2. Search for `Claude Haiku 4.5` and look at the **cross-Region model inference** quotas for requests per minute and
   tokens per minute.
3. Search for `Titan Text Embeddings V2` and look at its on-demand quotas.
4. If an applied value is 0 after the Marketplace subscription has finished, choose **Request increase at account
   level**. If the quota is not adjustable, open an AWS Support case.

## 6. Point the app at the account

```bash
export AWS_PROFILE=<your-profile>
```

```bash
export AWS_REGION=us-east-1
```

```bash
aws sts get-caller-identity
```

The last command should print the account you just set up.

## 7. Verify end to end

Follow [testing.md](testing.md): start the app, upload the sample PDF (this calls Titan) and ask a question (this
calls Claude).

## Switching models

The use-case form covers all Anthropic models, so switching (for example to `us.anthropic.claude-haiku-5-5`, as the
comment in `application.properties` suggests) only needs:
1. The new ID in `app.claude.model`.
2. The new IDs in the two Claude statements of the IAM policy.
3. A non-zero quota for the new model (step 5).

## Troubleshooting

| Error | Cause | Fix |
| --- | --- | --- |
| `Model use case details have not been submitted for this account` (404) | Anthropic form not submitted | Step 2 |
| `AccessDeniedException` right after submitting the form | Agreement or Marketplace subscription still pending | Wait up to 15 minutes, or for AWS's email, then retry |
| `Model access is denied due to IAM user or service role is not authorized to perform the required AWS Marketplace actions` | First invocation made by an identity without Marketplace permissions | Add the Marketplace statement (step 4), or have an administrator invoke the model once in the playground |
| `Your account is not authorized to perform this action` when submitting the form | No valid payment method, or unsupported billing country | Step 1, then submit again |
| `AccessDeniedException ... not authorized to perform: bedrock:InvokeModel` | IAM policy missing or wrong ARNs or region | Step 4; check the account ID and region in the ARNs |
| `ValidationException: Invocation of model ID ... with on-demand throughput isn't supported` | Bare model ID used for Claude | Use the `us.` inference profile ID in `app.claude.model` |
| `ThrottlingException` or "not available for this account" (403) | Quota is 0 or too low | Step 5 |
| `AccessDeniedException` although IAM allows everything | An AWS Organizations SCP blocks Bedrock or the routed regions | Ask the organization administrator to allow `bedrock:InvokeModel` in the US regions the profile uses |

References: [Request access to models](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html),
[Geographic cross-Region inference](https://docs.aws.amazon.com/bedrock/latest/userguide/geographic-cross-region-inference.html),
[Resolving "Access denied" errors](https://repost.aws/knowledge-center/bedrock-serverless-models-access-denied).
