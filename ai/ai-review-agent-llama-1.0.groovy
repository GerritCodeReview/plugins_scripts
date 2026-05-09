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
class AiLlamaReviewProvider implements AiReviewProvider {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()

    // Update this to your internal endpoint (e.g., Ollama or LocalAI)
    private static final String LLAMA_API_URL = 'http://10.0.0.50:8080/v1/chat/completions'

    final String displayName = 'Llama-Local'
    // Define the tags for your locally loaded models
    final Set<String> models = ['llama3:8b', 'llama3:70b', 'codellama'] as Set

    @Inject
    private CloseableHttpClient httpClient

    @Override
    String review(String apiKey, String model, String prompt) {
        def post = new HttpPost(LLAMA_API_URL).with {
            setHeader('Content-Type', 'application/json')
            // Most local engines ignore the API Key but require the header if using an OpenAI-compatible proxy
            if (apiKey) setHeader('Authorization', "Bearer $apiKey")

            def payload = [
                    model: model,
                    messages: [
                            [role: 'user', content: prompt]
                    ],
                    // Lower temperature is usually better for code reviews to reduce "hallucinations"
                    temperature: 0.2
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
                    throw new IOException("Local Llama engine returned HTTP $code: $body")
                }
                return extractResponseText(body)
            } finally {
                response.close()
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log('Failed to call Local Llama API (model=%s)', model)
            throw new RuntimeException('Failed to call Local Llama API', e)
        }
    }

    private static String extractResponseText(String body) {
        def json = new JsonSlurper().parseText(body)

        // Standard OpenAI-compatible format used by Ollama/LocalAI
        def textContent = json.choices?.getAt(0)?.message?.content

        if (!textContent) {
            throw new IOException('Local LLM response contains no content in choices. Check if model is loaded.')
        }

        return textContent.trim()
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

class LlamaReviewProviderModule extends LifecycleModule {
    @Override
    protected void configure() {
        bind(CloseableHttpClient).toProvider(HttpClientProvider).in(Scopes.SINGLETON)
        listener().to(HttpClientProvider)
        DynamicSet.bind(binder(), AiReviewProvider).to(AiLlamaReviewProvider)
    }
}

modules = [ LlamaReviewProviderModule ]