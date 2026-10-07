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
import org.apache.http.client.methods.HttpPost
import org.apache.http.message.*
import org.apache.http.entity.StringEntity

import java.nio.charset.StandardCharsets

import groovy.json.*

@Singleton
class AiGeminiReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    // v1beta is intended for all calls (models listing, countTokens and generateContent):
    // preview models and the `-latest` aliases are not exposed on v1 and fail there with 404.
    private static final String GEMINI_API_URL_BASE = 'https://generativelanguage.googleapis.com/v1beta/models'
    private static final String API_KEY_HEADER = 'x-goog-api-key'
    private static final int MAX_ERROR_LEN = 500
    // Gemini variants that are not suitable for a text-only code review chat.
    private static final def NON_CHAT_MODEL =
            ~/-(image|tts|transcribe|live|custom-?tools)\b|robotics|computer-use|omni|nano-banana/
    private static final def LATEST_ALIAS = ~/^gemini-(.+)-latest$/
    private static final def MODEL_VERSION = ~/^gemini-(\d+(?:\.\d+)*)-/

    final String displayName = 'Gemini'

    @Inject
    private AiHttpClient http

    @Override
    Set<String> getModels(String apiKey) {
        Set<String> listed
        try {
            // Default page size (50) is smaller than the v1beta catalog.
            listed = http.get("${GEMINI_API_URL_BASE}?pageSize=1000",
                    [http.acceptApplicationJson(), apiKeyHeader(apiKey)] as Header[],
                    { extractErrorMessage(it) },
                    { extractModels(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Gemini API to fetch models')
            return [] as Set
        }

        def available = listed.findAll { isAvailable(apiKey, it) }
        if (!available) {
            logger.atWarning().log('None of the Gemini models listed for this key is available')
        }
        new LinkedHashSet<>(available.sort(false) { a, b -> compareModels(a, b) })
    }

    /**
     * ListModels also returns models that are retired for new users and fail with HTTP 404 on
     * generateContent. countTokens fails the same way but does not consume generation quota.
     * Any other failure (quota, network) keeps the model listed.
     */
    private boolean isAvailable(String apiKey, String model) {
        def request = new HttpPost("${GEMINI_API_URL_BASE}/${model}:countTokens")
        request.setHeaders([http.contentTypeApplicationJson(), apiKeyHeader(apiKey)] as Header[])
        request.setEntity(new StringEntity(new JsonBuilder([contents: [[parts: [[text: 'ping']]]]]).toString(),
                StandardCharsets.UTF_8))
        try {
            http.execute(request,
                    { it != HttpStatus.SC_NOT_FOUND } as StatusCodeHandler,
                    { extractErrorMessage(it) },
                    { true })
        } catch (AiCodeReviewException e) {
            logger.atInfo().log('Gemini model %s is not available: %s', model, e.message)
            false
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to check availability of Gemini model %s', model)
            true
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
        }?.collect { it.name.replace('models/', '') }?.findAll { !(it =~ NON_CHAT_MODEL) } as Set

        if (!fetchedModels) {
            logger.atWarning().log("Gemini did not return any model enabled for this key")
            [] as Set
        } else {
            fetchedModels
        }
    }

    /**
     * `-latest` aliases first, then newest version first; within a version pro, flash, flash-lite,
     * with stable models before previews.
     */
    private static int compareModels(String a, String b) {
        boolean aLatest = a ==~ LATEST_ALIAS
        boolean bLatest = b ==~ LATEST_ALIAS
        if (aLatest != bLatest) return aLatest ? -1 : 1
        int byVersion = compareVersions(versionOf(b), versionOf(a))
        if (byVersion != 0) return byVersion
        int byTier = tierOf(a) <=> tierOf(b)
        if (byTier != 0) return byTier
        int byPreview = a.contains('-preview') <=> b.contains('-preview')
        byPreview != 0 ? byPreview : a <=> b
    }

    private static List<Integer> versionOf(String model) {
        def matcher = model =~ MODEL_VERSION
        matcher.find() ? matcher.group(1).tokenize('.').collect { it as int } : []
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int byPart = (i < a.size() ? a[i] : 0) <=> (i < b.size() ? b[i] : 0)
            if (byPart != 0) return byPart
        }
        a.size() <=> b.size()
    }

    private static int tierOf(String model) {
        if (model.contains('-pro')) return 0
        if (model.contains('-flash-lite')) return 2
        model.contains('-flash') ? 1 : 3
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