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
class AiClaudeReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private static final String CLAUDE_API_URL = 'https://api.anthropic.com/v1/messages'
    private static final String ANTHROPIC_VERSION = '2023-06-01'

    final String displayName = 'Claude'
    final Set<String> models = ['claude-3-5-sonnet-20240620', 'claude-3-opus-20240229', 'claude-3-haiku-20240307'] as Set

    @Inject
    private CloseableHttpClient httpClient

    @Override
    String review(String apiKey, String model, String prompt) {
        def post = new HttpPost(CLAUDE_API_URL).with {
            setHeader('Content-Type', 'application/json')
            setHeader('x-api-key', apiKey)
            setHeader('anthropic-version', ANTHROPIC_VERSION)

            def payload = [
                    model: model,
                    max_tokens: 4096,
                    messages: [
                            [role: 'user', content: prompt]
                    ]
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
                    throw new IOException("Claude API returned HTTP $code: ${extractErrorMessage(body)}")
                }
                return extractResponseText(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Claude API (model=%s)', model)
            throw new RuntimeException('Failed to call Claude API', e)
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        // Claude returns a 'content' array of objects with 'type' and 'text'
        def textContent = json.content?.find { it.type == 'text' }?.text

        if (!textContent) {
            throw new IOException('Claude API response contains no text content')
        }

        return textContent
    }

    private static String extractErrorMessage(String body) {
        try {
            def json = new JsonSlurper().parseText(body)
            if (json?.error) return "[${json.error.type}] ${json.error.message}"
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

class ClaudeReviewProviderModule extends LifecycleModule {
    @Override
    protected void configure() {
        bind(CloseableHttpClient).toProvider(HttpClientProvider).in(Scopes.SINGLETON)
        listener().to(HttpClientProvider)
        DynamicSet.bind(binder(), AiReviewProvider).to(AiClaudeReviewProvider)
    }
}

modules = [ ClaudeReviewProviderModule ]