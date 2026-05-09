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
import com.google.inject.*
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.http.client.methods.*
import org.apache.http.entity.StringEntity
import org.apache.http.client.HttpClient;
import org.apache.http.util.EntityUtils
import java.nio.charset.StandardCharsets

@Singleton
class AiGeminiReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String GEMINI_API_URL_BASE = 'https://generativelanguage.googleapis.com/v1/models'

    final String displayName = 'Gemini'

    @Inject
    private HttpClient httpClient

    @Override
    Set<String> getModels(String apiKey) {
        def get = new HttpGet(GEMINI_API_URL_BASE).with {
            setHeader('Accept', 'application/json')
            setHeader('x-goog-api-key', apiKey)
            return it
        }

        try {
            def response = httpClient.execute(get)
            try {
                int code = response.statusLine.statusCode
                String body = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)

                if (!(code in 200..299)) {
                    logger.atWarning().log('Failed to list Gemini models. HTTP %d: %s', code, extractErrorMessage(body))
                    return [] as Set
                }

                extractModels(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Gemini API to fetch models')
            return [] as Set
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        def post = new HttpPost("${GEMINI_API_URL_BASE}/${model}:generateContent").with {
            setHeader('Content-Type', 'application/json')
            setHeader('x-goog-api-key', apiKey)
            // JsonBuilder eliminates the need for Gson and the entire GeminiApi class tree
            entity = new StringEntity(
                    new JsonBuilder([contents: [[parts: [[text: prompt]]]]]).toString(),
                    StandardCharsets.UTF_8
            )
            return it
        }

        try {
            def response = httpClient.execute(post)
            try {
                int code = response.statusLine.statusCode
                String body = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)

                if (!(code in 200..299)) {
                    throw new IOException("Gemini API returned HTTP $code: ${extractErrorMessage(body)}")
                }
                return extractResponseText(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Gemini API (model=%s)', model)
            throw new RuntimeException('Failed to call Gemini API', e)
        }
    }

    private static Set<String> extractModels(String body) {
        def json = new JsonSlurper().parseText(body)

        def fetchedModels = json.models?.findAll { model ->
            model.supportedGenerationMethods?.contains('generateContent') &&
                    model.name?.startsWith('models/gemini')
        }?.collect {
            it.name.replace('models/', '')
        } as Set

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

        def text = candidate.content.parts?.findResults { it.text }?.join()
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
        return body.length() > 500 ? "${body.take(500)}..." : body
    }
}

class AiGeminiModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiGeminiReviewProvider)
    }
}

modules = [ AiGeminiModule ]