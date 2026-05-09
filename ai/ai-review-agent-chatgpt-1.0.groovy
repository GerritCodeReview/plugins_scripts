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
import java.net.http.HttpHeaders

@Singleton
class AiChatGptReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String OPENAI_API_URL = 'https://api.openai.com/v1/chat/completions'
    private static final int MAX_ERROR_LEN = 500
    private static final String displayName = 'ChatGPT'
    private static final Set<String> models = ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo'] as Set

    @Inject
    private AiHttpClient http

    @Override
    String review(String apiKey, String model, String prompt) {
        try {
            def response = http.post(OPENAI_API_URL,
                    [http.contentTypeApplicationJson(), authHeader(apiKey)] as Header[],
                    new StringEntity(new JsonBuilder([
                            model      : model,
                            messages   : [
                                    [role: 'user', content: prompt]
                            ],
                            temperature: 0.7
                    ]), StandardCharsets.UTF_8),
                    { extractErrorMessage(it) },
                    { extractResponseText(it) })
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call OpenAI API (model=%s)', model)
            throw new RuntimeException('Failed to call OpenAI API', e)
        }
    }

    private static Header authHeader(String apiKey) {
        new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
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

class AiChatGptModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiChatGptReviewProvider)
    }
}

module = ChatGptReviewProviderModule