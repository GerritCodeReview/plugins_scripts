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
class AiGeminiReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String GEMINI_API_URL_BASE = 'https://generativelanguage.googleapis.com/v1/models'
    private static final String API_KEY_HEADER = 'x-goog-api-key'
    private static final int MAX_ERROR_LEN = 500

    final String displayName = 'Gemini'

    @Inject
    private AiHttpClient http

    @Override
    Set<String> getModels(String apiKey) {
        try {
            http.get(GEMINI_API_URL_BASE,
                    [http.acceptApplicationJson(), apiKeyHeader(apiKey)] as Header[],
                    { extractErrorMessage(it) },
                    { extractModels(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Gemini API to fetch models')
            return [] as Set
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            http.post("${GEMINI_API_URL_BASE}/${model}:generateContent",
                    [http.contentTypeApplicationJson(), apiKeyHeader(apiKey)] as Header[],
                    new StringEntity(new JsonBuilder([contents: [[parts: [[text: prompt]]]]]).toString(),
                            StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Gemini API (model=%s)', model)
            throw new IllegalStateException('Failed to call Gemini API', e)
        }
    }

    private static Header apiKeyHeader(String apiKey) {
        new BasicHeader(API_KEY_HEADER, apiKey)
    }

    private static Set<String> extractModels(String body) {
        def json = new JsonSlurper().parseText(body)

        def fetchedModels = json.models?.findAll {
            it.supportedGenerationMethods?.contains('generateContent') &&
                    it.name?.startsWith('models/gemini')
        }?.collect { it.name.replace('models/', '') } as Set

        if (!fetchedModels) {
            logger.atWarning().log("Gemini did not return any model enabled for this key")
            [] as Set
        } else {
            fetchedModels
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        def candidate = json.candidates?.find()
        if (!candidate) {
            throw new IOException('Gemini API returned no candidates')
        }

        if (!candidate.content) {
            def reason = candidate.finishReason ? candidate.finishReason : 'unknown'
            throw new IOException("Gemini API candidate has no content, finishReason=$reason")
        }

        def text = candidate.content.parts?.findResults { it.text }?.join('\n')
        if (!text) throw new IOException('Gemini API response contains no text parts')

        return text
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.status}] ${json.error.message}"
        } catch (Exception e) {
            logger.atWarning().withCause(e).log('Failed to parse error response')
        }
        return body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body
    }
}

class AiGeminiModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiGeminiReviewProvider)
    }
}

module = AiGeminiModule