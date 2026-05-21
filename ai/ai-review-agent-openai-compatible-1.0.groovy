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
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.registration.DynamicSet
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.inject.*

import org.apache.http.*
import org.apache.http.message.*
import org.apache.http.entity.StringEntity

import java.nio.charset.StandardCharsets

import groovy.json.*

@Singleton
class AiOpenAiCompatibleReviewProvider implements AiReviewProvider {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final String DEFAULT_URL = 'http://localhost:3000/api/v1'
  private static final int MAX_ERROR_LEN = 500

  final String displayName = 'OpenAI Compatible'

  private final String baseUrl
  private final AiHttpClient http

  @Inject
  AiOpenAiCompatibleReviewProvider(PluginConfigFactory configFactory, @PluginName String pluginName, AiHttpClient http) {
    def config = configFactory.getFromGerritConfig(pluginName)
    this.baseUrl = config.getString('baseUrl', DEFAULT_URL)
    this.http = http
  }

  @Override
  Set<String> getModels(String notUsed) {
    List<Map> catalog = http.get(
        baseUrl + '/models',
        [http.acceptApplicationJson()] as Header[],
        { extractErrorMessage(it) },
        { extractCatalog(it) }) as List<Map>

    // Paid first (strongest quality), then DeepSeek flagship, then free picks.
    [(versioned.max { it.version }).id as String]

    def ids = new LinkedHashSet<>(catalog.collect {it.id as String})
    logger.atInfo().log('Open AI Compatible catalog refreshed (%d models cached)', ids.size())
    return ids

  }

  @Override
  String review(String apiKey, String model, String prompt) {
    Header[] headers = [
        http.contentTypeApplicationJson(),
        new BasicHeader('Authorization', "Bearer ${apiKey}"),
    ] as Header[]
    def entity = new StringEntity(
        new JsonBuilder([
            model   : model,
            messages: [[role: 'user', content: prompt]],
        ]).toString(),
        StandardCharsets.UTF_8)

    try {
      return http.post(
          baseUrl + '/chat/completions',
          headers,
          entity,
          { extractErrorMessage(it) },
          { extractResponseText(it) }) as String
    } catch (JsonException | IOException e) {
      logger.atWarning().withCause(e).log('Failed to call Open AI Compatible API (model=%s)', model)
      throw new IllegalStateException('Failed to call Open AI Compatible API', e)
    }
  }

  private static String extractResponseText(String body) {
    def json = new JsonSlurper().parseText(body)

    def choice = json.choices?.find()
    if (!choice) {
      throw new IOException('Open AI Compatible API returned no choices')
    }

    def content = choice.message?.content
    if (!content) {
      def reason = choice.finish_reason ? choice.finish_reason : 'unknown'
      throw new IOException("Open AI Compatible API choice has no content, finish_reason=$reason")
    }

    // Prefix \n so the reply sits below the "Gathering ..." placeholder.
    String text = unwrapOuterMarkdownFence(content as String)
    return text ? "\n${text}" : text
  }

  private static List<Map> extractCatalog(String body) {
    def json = new JsonSlurper().parseText(body)
    return (json?.data ?: []) as List<Map>
  }

  // Strip outer ```lang ... ``` wrap (gpt-oss-* habit) so the chat panel
  // renders it as markdown, not a literal code block.
  private static String unwrapOuterMarkdownFence(String text) {
    if (!text) return text
    String trimmed = text.trim()
    if (!trimmed.startsWith('```') || !trimmed.endsWith('```')) return text
    int firstNewline = trimmed.indexOf('\n')
    if (firstNewline < 0) return text
    String openFence = trimmed.substring(0, firstNewline).trim()
    if (!(openFence ==~ /```[A-Za-z0-9_+\-]*/)) return text
    String inner = trimmed.substring(firstNewline + 1, trimmed.length() - 3)
    if (inner.contains('```')) return text
    return inner.trim()
  }

  private static String extractErrorMessage(String body) {
    try {
      def json = new JsonSlurper().parseText(body)
      if (json?.error) return "[${json.error.code}] ${json.error.message}"
    } catch (Exception e) {
      logger.atWarning().withCause(e).log('Failed to parse error response')
    }
    return body.length() > MAX_ERROR_LEN ? "${body.take(MAX_ERROR_LEN)}..." : body
  }
}

class AiOpenAiCompatibleModule extends AbstractModule {
  @Override
  protected void configure() {
    DynamicSet.bind(binder(), AiReviewProvider).to(AiOpenRouterReviewProvider)
  }
}

module = AiOpenAiCompatibleModule
