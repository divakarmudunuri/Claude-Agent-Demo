package com.example.Claude_Agent_Demo.config;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Configuration
public class ClaudeConfig {

	/**
	 * Anthropic Java SDK client pointed at Claude on Amazon Bedrock (bedrock-runtime endpoint),
	 * authenticated with SigV4 through the default AWS credential chain.
	 */
	@Bean(destroyMethod = "close")
	public AnthropicClient anthropicClient(@Value("${app.aws.region}") String region) {
		return AnthropicOkHttpClient.builder()
				.backend(BedrockBackend.builder()
						.awsCredentialsProvider(DefaultCredentialsProvider.builder().build())
						.region(Region.of(region))
						.build())
				.build();
	}

}
