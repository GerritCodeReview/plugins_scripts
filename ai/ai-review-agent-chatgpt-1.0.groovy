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
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.nio.charset.StandardCharsets

@Singleton
class AiChatGptReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String OPENAI_API_URL = 'https://api.openai.com/v1/chat/completions'

    final String displayName = 'ChatGPT'
    // Updated to current GPT-4o and GPT-4 Turbo models
    final Set<String> models = ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo'] as Set

    @Inject
    private CloseableHttpClient httpClient

    @Override
    String review(String apiKey, String model, String prompt) {
        def post = new HttpPost(OPENAI_API_URL).with {
            setHeader('Content-Type', 'application/json')
            setHeader('Authorization', "Bearer $apiKey")

            // OpenAI payload structure
            def payload = [
                    model: model,
                    messages: [
                            [role: 'user', content: prompt]
                    ],
                    temperature: 0.7
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
                    throw new IOException("OpenAI API returned HTTP $code: ${extractErrorMessage(body)}")
                }
                return extractResponseText(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call OpenAI API (model=%s)', model)
            throw new RuntimeException('Failed to call OpenAI API', e)
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        // OpenAI returns a 'choices' array; the message is in the first choice
        def textContent = json.choices?.getAt(0)?.message?.content

        if (!textContent) {
            throw new IOException('OpenAI API response contains no content in choices')
        }

        return textContent.trim()
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.code ?: 'Error'}] ${json.error.message}"
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

class ChatGptReviewProviderModule extends LifecycleModule {
    @Override
    protected void configure() {
        bind(CloseableHttpClient).toProvider(HttpClientProvider).in(Scopes.SINGLETON)
        listener().to(HttpClientProvider)
        DynamicSet.bind(binder(), AiReviewProvider).to(AiChatGptReviewProvider)
    }
}

modules = [ ChatGptReviewProviderModule ]