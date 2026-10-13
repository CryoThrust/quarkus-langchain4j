package io.quarkiverse.langchain4j.mcp.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.quarkiverse.langchain4j.mcp.runtime.TracingMcpClientListener;
import io.quarkiverse.langchain4j.mcp.runtime.config.McpTransportType;

/** Local review controls: released MCP client + real loopback JSON-RPC, without Quarkus boot. */
class McpTracingWireReviewTest {
    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey("jsonrpc.request.id");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<Throwable> serverErrors = new CopyOnWriteArrayList<>();
    private final CyclicBarrier overlappingCalls = new CyclicBarrier(2);
    private HttpServer server;
    private ExecutorService workers;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;
    private DefaultMcpClient client;

    @BeforeEach
    void start() throws IOException {
        workers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/mcp", this::respond);
        server.start();
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        tracer = provider.get("wire-review");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        TracingMcpClientListener listener = new TracingMcpClientListener(tracer,
                W3CTraceContextPropagator.getInstance(), McpTransportType.STREAMABLE_HTTP, url, null);
        client = DefaultMcpClient.builder()
                .transport(StreamableHttpMcpTransport.builder().url(url).setHttpVersion1_1().build())
                .protocolVersion("2026-07-28")
                .autoHealthCheck(false)
                .subscribeToToolListChanges(false).subscribeToPromptListChanges(false).subscribeToResourceListChanges(false)
                .toolExecutionTimeout(Duration.ofSeconds(15))
                .addListener(listener).metaSupplier(listener).build();
        exporter.reset();
        requests.clear();
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (provider != null) {
            provider.close();
        }
        assertThat(serverErrors).isEmpty();
    }

    @Test
    void overlappingAsyncCallsKeepDistinctParentsAndWireTraceparents() throws Exception {
        checkOverlappingCalls(false);
    }

    @Test
    void overlappingAsyncErrorDoesNotMarkTheSuccessfulSibling() throws Exception {
        checkOverlappingCalls(true);
    }

    private void checkOverlappingCalls(boolean secondFails) throws Exception {
        Span firstParent = tracer.spanBuilder("first-parent").startSpan();
        Span secondParent = tracer.spanBuilder("second-parent").startSpan();
        CompletableFuture<ToolExecutionResult> first;
        CompletableFuture<ToolExecutionResult> second;
        try {
            try (Scope ignored = firstParent.makeCurrent()) {
                first = client.executeToolAsync(request("overlap", false), null);
                assertThat(Span.current()).isSameAs(firstParent);
            }
            try (Scope ignored = secondParent.makeCurrent()) {
                second = client.executeToolAsync(request("overlap", secondFails), null);
                assertThat(Span.current()).isSameAs(secondParent);
            }
            String firstResult = first.get(20, TimeUnit.SECONDS).resultText();
            if (secondFails) {
                assertThat(second.handle((value, error) -> error).get(20, TimeUnit.SECONDS)).isNotNull();
            } else {
                assertThat(second.get(20, TimeUnit.SECONDS).resultText()).startsWith("00-");
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(toolSpans("overlap")).hasSize(2));
            SpanData firstSpan = toolSpans("overlap").stream()
                    .filter(s -> s.getParentSpanId().equals(firstParent.getSpanContext().getSpanId())).findFirst()
                    .orElseThrow();
            SpanData secondSpan = toolSpans("overlap").stream()
                    .filter(s -> s.getParentSpanId().equals(secondParent.getSpanContext().getSpanId())).findFirst()
                    .orElseThrow();
            assertThat(firstSpan.getTraceId()).isEqualTo(firstParent.getSpanContext().getTraceId());
            assertThat(secondSpan.getTraceId()).isEqualTo(secondParent.getSpanContext().getTraceId());
            assertThat(firstSpan.getTraceId()).isNotEqualTo(secondSpan.getTraceId());
            assertThat(firstSpan.getAttributes().get(REQUEST_ID)).isNotEqualTo(secondSpan.getAttributes().get(REQUEST_ID));
            assertThat(firstSpan.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
            assertThat(firstSpan.getAttributes().get(ERROR_TYPE)).isNull();
            assertThat(secondSpan.getStatus().getStatusCode() == StatusCode.ERROR).isEqualTo(secondFails);
            assertThat(secondSpan.getAttributes().get(ERROR_TYPE)).isEqualTo(secondFails ? "tool_error" : null);
            assertThat(firstResult).isEqualTo(McpTracingTestBase.traceparentOf(firstSpan));
            for (SpanData span : List.of(firstSpan, secondSpan)) {
                JsonNode wire = requests.stream()
                        .filter(r -> r.path("id").asText().equals(span.getAttributes().get(REQUEST_ID))).findFirst()
                        .orElseThrow();
                assertThat(wire.at("/params/_meta/traceparent").asText()).isEqualTo(McpTracingTestBase.traceparentOf(span));
            }
        } finally {
            firstParent.end();
            secondParent.end();
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        String standaloneResult = client.executeTool(request("standalone", false)).resultText();
        SpanData standalone = toolSpans("standalone").get(0);
        assertThat(standalone.getParentSpanContext().isValid()).isFalse();
        assertThat(standaloneResult).isEqualTo(McpTracingTestBase.traceparentOf(standalone));
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void paginationInjectsOneOperationSpanIntoBothActualRequests() {
        Span parent = tracer.spanBuilder("list-parent").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            assertThat(client.listTools()).extracting(s -> s.name()).containsExactly("first", "second");
            assertThat(Span.current()).isSameAs(parent);
        } finally {
            parent.end();
        }
        List<SpanData> spans = exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("tools/list")).toList();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("id").asText()).isNotEqualTo(requests.get(1).path("id").asText());
        assertThat(span.getAttributes().get(REQUEST_ID)).isEqualTo(requests.get(0).path("id").asText());
        for (JsonNode wire : requests) {
            assertThat(wire.at("/params/_meta/traceparent").asText()).isEqualTo(McpTracingTestBase.traceparentOf(span));
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    private List<SpanData> toolSpans(String name) {
        return exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("tools/call " + name)).toList();
    }

    private static ToolExecutionRequest request(String name, boolean fail) {
        return ToolExecutionRequest.builder().name(name).arguments("{\"fail\":" + fail + "}").build();
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            Object result;
            switch (request.path("method").asText()) {
                case "server/discover":
                    result = Map.of("supportedVersions", List.of("2026-07-28"), "capabilities", Map.of("tools", Map.of()));
                    break;
                case "tools/list":
                    boolean last = request.at("/params/cursor").asText().equals("page-2");
                    Map<String, Object> tool = Map.of("name", last ? "second" : "first", "inputSchema",
                            Map.of("type", "object"));
                    result = last ? Map.of("tools", List.of(tool)) : Map.of("tools", List.of(tool), "nextCursor", "page-2");
                    break;
                case "tools/call":
                    if (request.at("/params/name").asText().equals("overlap")) {
                        overlappingCalls.await(10, TimeUnit.SECONDS);
                    }
                    result = Map.of("isError", request.at("/params/arguments/fail").asBoolean(),
                            "content",
                            List.of(Map.of("type", "text", "text", request.at("/params/_meta/traceparent").asText())));
                    break;
                default:
                    throw new AssertionError("Unexpected request: " + request);
            }
            byte[] response = mapper
                    .writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable error) {
            serverErrors.add(error);
            byte[] body = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
