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

import com.gerritforge.gerrit.plugins.ai.provider.api.*

import com.google.common.flogger.FluentLogger
import com.google.gerrit.extensions.registration.DynamicSet
import com.google.inject.*

import org.apache.http.*
import org.apache.http.message.*
import org.apache.http.entity.StringEntity

import java.nio.charset.StandardCharsets

import groovy.json.*

@Singleton
class AiClaudeReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final int MAX_ERROR_LEN = 500
    private static final String CLAUDE_API_URL = 'https://api.anthropic.com/v1'
    private static final String API_KEY_HEADER = 'x-api-key'
    private static final Header ANTHROPIC_VERSION_HEADER = new BasicHeader('anthropic-version', '2023-06-01')

    final String displayName = 'Claude'

    @Inject
    private AiHttpClient http

    @Override
    Set<String> getModels(String apiKey) {
        try {
            http.get(
                    "${CLAUDE_API_URL}/models",
                    [http.acceptApplicationJson(), apiKeyHeader(apiKey), ANTHROPIC_VERSION_HEADER] as Header[],
                    { extractErrorMessage(it) },
                    { extractResponseModels(it) })
        } catch (IOException | JsonException e) {
            logger.atWarning().withCause(e).log('Failed to call Claude API to get the list of models')
            [] as Set
        }
    }


    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            http.post("${CLAUDE_API_URL}/messages",
                    [http.contentTypeApplicationJson(), apiKeyHeader(apiKey), ANTHROPIC_VERSION_HEADER] as Header[],
                    new StringEntity(new JsonBuilder([
                            model     : model,
                            max_tokens: 4096,
                            messages  : [
                                    [role: 'user', content: prompt]
                            ]
                    ]).toString(), StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        }
        catch (IOException | JsonException e) {
            logger.atWarning().withCause(e).log('Failed to call Claude API (model=%s)', model)
            throw new IllegalStateException('Failed to call Claude API', e)
        }
    }

    private static Header apiKeyHeader(String apiKey) {
        new BasicHeader(API_KEY_HEADER, apiKey)
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

    private static Set<String> extractResponseModels(String body) {
        def json = new JsonSlurper().parseText(body)

        // Claude returns a 'content' array of objects with 'type' and 'text'
        def modelsIds = json.data?.collect { it.id }

        if (!modelsIds) {
            throw new IOException('Claude API response contains no models')
        }

        return modelsIds as Set
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.type}] ${json.error.message}"
        } catch (Exception e) {
            logger.atWarning().withCause(e).log('Failed to parse error response')
        }
        return body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body
    }

}


class AiClaudeModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiClaudeReviewProvider)
    }
}

module = AiClaudeModule