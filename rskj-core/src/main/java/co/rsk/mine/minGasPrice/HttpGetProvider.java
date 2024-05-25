package co.rsk.mine.minGasPrice;

import java.time.Duration;

public class HttpGetProvider extends ConversionRateProvider {
    private final String url;
    private final String apiKey;
    private final String jsonPath;
    private final Duration timeout;

    public HttpGetProvider(
            String url, String apiKey,
            String jsonPath,
            Duration timeout
    ) {
        super("HTTP_GET");
        this.url = url;
        this.apiKey = apiKey;
        this.jsonPath = jsonPath;
        this.timeout = timeout;
    }

    public String getUrl() {
        return url;
    }

    public String getJsonPath() {
        return jsonPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getApiKey() {
        return apiKey;
    }

    @Override
    public long getGasPrice() {
        return 0;
    }
}
