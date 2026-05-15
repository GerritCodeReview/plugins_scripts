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
class AiOpenRouterReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String OPENROUTER_API_URL = 'https://openrouter.ai/api/v1/chat/completions'
    private static final String OPENROUTER_MODELS_URL = 'https://openrouter.ai/api/v1/models'
    private static final String ATTRIBUTION_REFERER = 'https://gerrit-review.googlesource.com/'
    private static final String ATTRIBUTION_TITLE = 'Gerrit AI Review'
    private static final int MAX_ERROR_LEN = 500

    // Floating aliases that always resolve to the latest vendor version.
    // DeepSeek (no `~latest` alias) is sourced dynamically below.
    private static final List<String> PAID_LATEST_MODELS = [
            '~anthropic/claude-opus-latest',
            '~anthropic/claude-sonnet-latest',
            '~google/gemini-pro-latest',
            '~openai/gpt-latest',
            '~anthropic/claude-haiku-latest',
            '~google/gemini-flash-latest',
            '~openai/gpt-mini-latest',
    ]

    private static final int FREE_PICK_COUNT = 5

    final String displayName = 'OpenRouter'

    @Inject
    private AiHttpClient http

    @Override
    Set<String> getModels(String notUsed) {
        return fetchAndMergeModels()
    }

    // On failure, return paid-only fallback. Caching handled by AiProvidersInfoCache.
    private LinkedHashSet<String> fetchAndMergeModels() {
        try {
            List<Map> catalog = http.get(
                    OPENROUTER_MODELS_URL,
                    [http.acceptApplicationJson()] as Header[],
                    { extractErrorMessage(it) },
                    { extractCatalog(it) }) as List<Map>

            // Paid first (strongest quality), then DeepSeek flagship, then free picks.
            LinkedHashSet<String> merged = new LinkedHashSet<>()
            merged.addAll(PAID_LATEST_MODELS)
            merged.addAll(latestDeepseekProFromCatalog(catalog))
            merged.addAll(topFreeFromCatalog(catalog))

            logger.atInfo().log('OpenRouter catalog refreshed (%d models cached)', merged.size())
            return merged
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log(
                    'Failed to fetch OpenRouter catalog; falling back to ~latest paid models only')
            return PAID_LATEST_MODELS as LinkedHashSet
        }
    }

    @Override
    String review(String apiKey, String model, String prompt) {
        Header[] headers = [
                http.contentTypeApplicationJson(),
                new BasicHeader('Authorization', "Bearer ${apiKey}"),
                new BasicHeader('HTTP-Referer', ATTRIBUTION_REFERER),
                new BasicHeader('X-Title', ATTRIBUTION_TITLE),
        ] as Header[]
        def entity = new StringEntity(
                new JsonBuilder([
                        model   : model,
                        messages: [[role: 'user', content: prompt]],
                ]).toString(),
                StandardCharsets.UTF_8)

        try {
            return http.post(
                    OPENROUTER_API_URL,
                    headers,
                    entity,
                    { extractErrorMessage(it) },
                    { extractResponseText(it) }) as String
        } catch (JsonException | IOException e) {
            logger.atWarning().withCause(e).log('Failed to call OpenRouter API (model=%s)', model)
            throw new IllegalStateException('Failed to call OpenRouter API', e)
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        def choice = json.choices?.find()
        if (!choice) {
            throw new IOException('OpenRouter API returned no choices')
        }

        def content = choice.message?.content
        if (!content) {
            def reason = choice.finish_reason ? choice.finish_reason : 'unknown'
            throw new IOException("OpenRouter API choice has no content, finish_reason=$reason")
        }

        // Prefix \n so the reply sits below the "Gathering ..." placeholder.
        String text = unwrapOuterMarkdownFence(content as String)
        return text ? "\n${text}" : text
    }

    private static List<Map> extractCatalog(String body) {
        def json = new JsonSlurper().parseText(body)
        return (json?.data ?: []) as List<Map>
    }

    // Empirically more reliable free-tier namespaces (fewer 404 rotations).
    private static final Set<String> BIG_VENDORS = [
            'openai', 'anthropic', 'google', 'meta-llama',
            'deepseek', 'qwen', 'z-ai', 'mistralai', 'nvidia',
    ] as Set

    // Heuristic "good for code review" score (catalog payload lacks benchmarks):
    //   +100  id contains `coder` / `code`           — code-specialized
    //   +60   reasoning mode (supported_parameters or `thinking` in id)
    //   +40   big-vendor namespace (see BIG_VENDORS)
    //   +ctx/10k                                     — tie-breaker
    // Re-tune if real-world picks regress.
    private static long scoreFreeModel(Map entry) {
        String id = (entry.id as String) ?: ''
        long score = 0L
        if (id.contains('coder') || id.contains('code')) score += 100L
        List params = (entry.supported_parameters as List) ?: []
        if (params.contains('reasoning') || id.contains('thinking')) score += 60L
        String vendor = id.contains('/') ? id.substring(0, id.indexOf('/')) : ''
        if (BIG_VENDORS.contains(vendor)) score += 40L
        score += ((entry.context_length ?: 0) as long) / 10_000L
        return score
    }

    // Top FREE_PICK_COUNT by scoreFreeModel(); id sort breaks ties stably.
    private static List<String> topFreeFromCatalog(List<Map> catalog) {
        return catalog
                .findAll { (it.id as String)?.endsWith(':free') }
                .sort { a, b ->
                    long sb = scoreFreeModel(b)
                    long sa = scoreFreeModel(a)
                    if (sb != sa) return Long.compare(sb, sa)
                    return ((a.id as String) ?: '').compareTo((b.id as String) ?: '')
                }
                .take(FREE_PICK_COUNT)
                .collect { it.id as String }
    }

    // Approximate "deepseek-latest": highest version `deepseek/deepseek-v<N>-pro`.
    private static List<String> latestDeepseekProFromCatalog(List<Map> catalog) {
        def versionRegex = ~/^deepseek\/deepseek-v(\d+)(?:\.\d+)?-pro$/
        def versioned = catalog.findResults { entry ->
            def id = entry.id as String
            if (!id) return null
            def m = versionRegex.matcher(id)
            m.matches() ? [id: id, version: (m.group(1) as int)] : null
        }
        if (versioned.isEmpty()) return []
        return [(versioned.max { it.version }).id as String]
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

class AiOpenRouterModule extends AbstractModule {
    @Override
    protected void configure() {
        DynamicSet.bind(binder(), AiReviewProvider).to(AiOpenRouterReviewProvider)
    }
}

module = AiOpenRouterModule
