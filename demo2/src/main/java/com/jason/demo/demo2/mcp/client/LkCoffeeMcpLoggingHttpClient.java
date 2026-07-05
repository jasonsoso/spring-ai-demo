package com.jason.demo.demo2.mcp.client;

import com.jason.demo.demo2.mcp.client.config.LkCoffeeMcpTransportConfig;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Wraps {@link HttpClient} to log MCP HTTP responses at DEBUG level.
 */
public final class LkCoffeeMcpLoggingHttpClient extends HttpClient {

    private final HttpClient delegate;

    private LkCoffeeMcpLoggingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    public static HttpClient.Builder wrapBuilder(HttpClient.Builder delegate) {
        return new LoggingHttpClientBuilder(delegate);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        HttpResponse<T> response = delegate.send(request, responseBodyHandler);
        LkCoffeeMcpTransportConfig.logIncomingResponse(request, response);
        return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        return delegate.sendAsync(request, responseBodyHandler)
                .whenComplete((response, error) -> {
                    if (error == null) {
                        LkCoffeeMcpTransportConfig.logIncomingResponse(request, response);
                    }
                });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler)
                .whenComplete((response, error) -> {
                    if (error == null) {
                        LkCoffeeMcpTransportConfig.logIncomingResponse(request, response);
                    }
                });
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static final class LoggingHttpClientBuilder implements HttpClient.Builder {

        private final HttpClient.Builder delegate;

        private LoggingHttpClientBuilder(HttpClient.Builder delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            delegate.cookieHandler(cookieHandler);
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            delegate.connectTimeout(duration);
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            delegate.sslContext(sslContext);
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            delegate.sslParameters(sslParameters);
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            delegate.executor(executor);
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(Redirect policy) {
            delegate.followRedirects(policy);
            return this;
        }

        @Override
        public HttpClient.Builder version(Version version) {
            delegate.version(version);
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            delegate.priority(priority);
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            delegate.proxy(proxySelector);
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            delegate.authenticator(authenticator);
            return this;
        }

        @Override
        public HttpClient.Builder localAddress(InetAddress localAddress) {
            delegate.localAddress(localAddress);
            return this;
        }

        @Override
        public HttpClient build() {
            return new LkCoffeeMcpLoggingHttpClient(delegate.build());
        }
    }
}
