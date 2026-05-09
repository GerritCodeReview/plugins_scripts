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
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.inject.*

import org.apache.http.*
import org.apache.http.message.*
import org.apache.http.entity.StringEntity

import java.nio.charset.StandardCharsets

import groovy.json.*

@Singleton
class AiLlamaReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String DEFAULT_URL = 'http://localhost:11434'
    private static final int MAX_ERROR_LEN = 500

    final String displayName = 'Llama (Native)'
    private final String baseUrl

    private AiHttpClient http

    @Inject
    AiLlamaReviewProvider(PluginConfigFactory configFactory, @PluginName String pluginName, AiHttpClient http) {
        def config = configFactory.getFromGerritConfig(pluginName)
        // Ensure we strip trailing slashes and the /v1 suffix if the user migrated config
        // Native Ollama default usually omits the /v1 suffix
        this.baseUrl = config.getString('baseUrl', DEFAULT_URL)
                .replaceAll('/+$', '')
                .replaceAll('/v1$', '')
        this.http = http
    }

    @Override
    Set<String> getModels(String apiKey) {
        try {
            http.get("${baseUrl}/api/tags",
                    [http.acceptApplicationJson()] as Header[],
                    { extractErrorMessage(it) },
                    { extractModels(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to fetch native Llama models at %s', baseUrl)
            return [] as Set
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            // Native Llama 'generate' payload
            def payload = [
                    model: model,
                    prompt: prompt,
                    stream: false
            ]

            http.post("${baseUrl}/api/generate",
                    [http.contentTypeApplicationJson()] as Header[],
                    new StringEntity(new JsonBuilder(payload).toString(), StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call native Llama API at %s', baseUrl)
            throw new IllegalStateException('Failed to call Llama API', e)
        }
    }

    private static Set<String> extractModels(String body) {
        def json = new JsonSlurper().parseText(body)
        def fetchedModels = json.models?.collect { it.name } as Set
        return fetchedModels ?: ([] as Set)
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)
        def text = json.response
        if (!text) {
            throw new IOException('Native Llama API response contains no text')
        }
        text.trim()
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return json.error.toString()
        } catch (Exception ignored) {}
        return body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body
    }
}

class AiLlamaModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiLlamaReviewProvider)
    }
}

module = AiLlamaModule