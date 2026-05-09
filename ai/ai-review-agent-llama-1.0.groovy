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
    private static final String DEFAULT_URL = 'http://localhost:11434/v1'
    private static final int MAX_ERROR_LEN = 500

    final String displayName = 'Llama (Local)'
    private final String baseUrl

    @Inject
    private AiHttpClient http

    @Inject
    AiLlamaReviewProvider(PluginConfigFactory configFactory, @PluginName String pluginName) {
        // Reads from gerrit.config under [plugin "plugin-name"]
        def config = configFactory.getFromGerritConfig(pluginName)
        this.baseUrl = config.getString('baseUrl', DEFAULT_URL)
    }

    @Override
    Set<String> getModels(String apiKey) {
        try {
            http.get("${baseUrl}/models",
                    [http.acceptApplicationJson(), authHeader(apiKey)] as Header[],
                    { extractErrorMessage(it) },
                    { extractModels(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Llama API to fetch models at %s', baseUrl)
            return [] as Set
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            http.post("${baseUrl}/chat/completions",
                    [http.contentTypeApplicationJson(), authHeader(apiKey)] as Header[],
                    new StringEntity(new JsonBuilder([
                            model: model,
                            messages: [[role: 'user', content: prompt]],
                            stream: false
                    ]).toString(), StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Llama API at %s (model=%s)', baseUrl, model)
            throw new IllegalStateException('Failed to call Llama API', e)
        }
    }

    private static Header authHeader(String apiKey) {
        new BasicHeader('Authorization', "Bearer ${apiKey}")
    }

    private static Set<String> extractModels(String body) {
        def json = new JsonSlurper().parseText(body)
        def fetchedModels = json.data?.collect { it.id } as Set
        return fetchedModels ?: ([] as Set)
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)
        def text = json.choices?.getAt(0)?.message?.content
        if (!text) {
            throw new IOException('Llama API response contains no content')
        }
        text.trim()
    }

    private static String extractErrorMessage(String body) {
        def json = new JsonSlurper().parseText(body)
        json?.error ?
                json.error.toString() :
                (body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body)
    }
}

class AiLlamaModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiLlamaReviewProvider)
    }
}

module = AiLlamaModule