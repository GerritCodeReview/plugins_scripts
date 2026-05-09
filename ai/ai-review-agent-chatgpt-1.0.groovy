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
import org.apache.http.HttpHeaders

import groovy.json.*

@Singleton
class AiChatGptReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String OPENAI_API_URL = 'https://api.openai.com/v1'
    private static final int MAX_ERROR_LEN = 500
    private static final float CREATIVITY_TEMPERATURE = 0.7

    final String displayName = 'ChatGPT'

    @Inject
    private AiHttpClient http

    @Override
    Set<String> getModels(String apiKey) {
        try {
            http.get("${OPENAI_API_URL}/models",
                    [authHeader(apiKey)] as Header[],
                    { extractErrorMessage(it) },
                    { extractModelsList(it) }) as Set
        } catch (IOException | JsonException e) {
            logger.atWarning().withCause(e).log("Failed to fetch OpenAI models")
            [] as Set
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            def requestPayload = [
                    model: model,
                    input: prompt
            ]
            if (!isReasoningModel(model)) {
                requestPayload.temperature = CREATIVITY_TEMPERATURE
            }
            http.post("${OPENAI_API_URL}/responses",
                    [http.contentTypeApplicationJson(), authHeader(apiKey)] as Header[],
                    new StringEntity(new JsonBuilder(requestPayload).toString(), StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        } catch (IOException | JsonException e) {
            logger.atWarning().withCause(e).log('Failed to call OpenAI API (model=%s)', model)
            throw new IllegalStateException('Failed to call OpenAI API', e)
        }
    }

    private static boolean isReasoningModel(String model) {
        model.startsWith('o1') || model.startsWith('o3')
    }

    private static Header authHeader(String apiKey) {
        new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
    }

    private static Set<String> extractModelsList(String body) {
        def json = new JsonSlurper().parseText(body)

        if (!json.data) {
            throw new IOException('OpenAI API response contains no data array for models')
        }

        return json.data.collect { it.id as String }
                .findAll { it.startsWith('gpt-') } as Set
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        def textContent = new StringBuilder()

        json.output?.each { item ->
            if (item.type == 'message' && item.role == 'assistant') {
                item.content?.each { contentBlock ->
                    if (contentBlock.type == 'output_text') {
                        textContent.append(contentBlock.text)
                    }
                }
            }
        }

        if (textContent.length() == 0) {
            throw new IOException('OpenAI API response contains no valid text output')
        }

        return textContent.toString().trim()
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.code ?: 'Error'}] ${json.error.message}"
        } catch (Exception e) {
            logger.atWarning().withCause(e).log('Failed to parse error response')
        }
        return body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body
    }
}

class AiChatGptModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiChatGptReviewProvider)
    }
}

module = AiChatGptModule