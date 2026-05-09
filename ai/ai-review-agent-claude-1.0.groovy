// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.gerritforge.gerrit.plugins.ai.provider.api.AiReviewProvider
import com.google.common.flogger.FluentLogger
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.extensions.registration.DynamicSet
import com.google.gerrit.lifecycle.LifecycleModule
import com.google.inject.*
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.http.client.methods.*
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.nio.charset.StandardCharsets

@Singleton
class AiClaudeReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String CLAUDE_API_URL = 'https://api.anthropic.com/v1'
    private static final String ANTHROPIC_VERSION = '2023-06-01'

    final String displayName = 'Claude'

    @Inject
    private CloseableHttpClient httpClient

    @Override
    Set<String> getModels(String apiKey) {
        def get = new HttpGet("${CLAUDE_API_URL}/models").with {
            setHeader('x-api-key', apiKey)
            setHeader('anthropic-version', ANTHROPIC_VERSION)
            return it
        }

        try {
            def response = httpClient.execute(get)
            try {
                int code = response.statusLine.statusCode
                String body = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)
                if (!(code in 200..299)) {
                    throw new IOException("Claude API returned HTTP $code: ${extractErrorMessage(body)}")
                }

                extractResponseModels(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Claude API to get the list of models')
            []
        }
    }


    @Override
    String review(String apiKey, String model, String prompt) {
        def post = new HttpPost("${CLAUDE_API_URL}/messages").with {
            setHeader('Content-Type', 'application/json')
            setHeader('x-api-key', apiKey)
            setHeader('anthropic-version', ANTHROPIC_VERSION)

            def payload = [
                    model: model,
                    max_tokens: 4096,
                    messages: [
                            [role: 'user', content: prompt]
                    ]
            ]

            entity = new StringEntity(new JsonBuilder(payload).toString(), StandardCharsets.UTF_8)
            return it
        }

        try {
            def response = httpClient.execute(post)
            try {
                int code = response.statusLine.statusCode
                String body = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)

                if (!(code in 200..299)) {
                    throw new IOException("Claude API returned HTTP $code: ${extractErrorMessage(body)}")
                }
                extractResponseText(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Claude API (model=%s)', model)
            throw new RuntimeException('Failed to call Claude API', e)
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        // Claude returns a 'content' array of objects with 'type' and 'text'
        def textContent = json.content?.find { it.type == 'text' }?.text

        if (!textContent) {
            throw new IOException('Claude API response contains no text content')
        }

        return textContent
    }

    private static List<String> extractResponseModels(String body) {
        def json = new JsonSlurper().parseText(body)

        // Claude returns a 'content' array of objects with 'type' and 'text'
        def modelsIds = json.data?.collect { it.id }

        if (!modelsIds) {
            throw new IOException('Claude API response contains no models')
        }

        return modelsIds
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.type}] ${json.error.message}"
        } catch (Exception e) {
            logger.atWarning().withCause(e).log('Failed to parse error response')
        }
        return body.length() > 500 ? "${body.take(500)}..." : body
    }
}

@Singleton
class HttpClientProvider implements Provider<CloseableHttpClient>, LifecycleListener {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private final CloseableHttpClient client = HttpClients.createDefault()

    @Override CloseableHttpClient get() { client }
    @Override void start() {}
    @Override void stop() {
        try {
            client.close()
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to close HTTP client')
        }
    }
}

class ClaudeReviewProviderModule extends LifecycleModule {
    @Override
    protected void configure() {
        bind(CloseableHttpClient).toProvider(HttpClientProvider).in(Scopes.SINGLETON)
        listener().to(HttpClientProvider)
        DynamicSet.bind(binder(), AiReviewProvider).to(AiClaudeReviewProvider)
    }
}

modules = [ ClaudeReviewProviderModule ]