package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.floor;

public class ServiceImpl implements Service {

    private static final int MEMORY_LIMIT_BYTES = 8 * 1024 * 1024;
    private static final int CONNECTION_TIMEOUT_MS = 250;
    private static final String DEFAULT_PATH = "/v0/entity";
    private static final String TIMESTAMP_HEADER = "Request-timestamp";
    private static final String WRONG_ACK = "wrong 'ack' value";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private static final Response badIdResponse
            = new Response(Response.BAD_REQUEST, "Invalid id".getBytes(StandardCharsets.UTF_8));
    private static final Response wrongAckResponse
            = new Response(Response.BAD_REQUEST, WRONG_ACK.getBytes(StandardCharsets.UTF_8));
    private final ServiceConfig config;
    private final Executor responseExecutor;
    private final RequestHandler requestHandler;
    private RendezvousRouter router;
    private DaoWrapper daoWrapper;
    private HttpServerImpl server;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.of(CONNECTION_TIMEOUT_MS, ChronoUnit.MILLIS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        responseExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        requestHandler = new RequestHandler(client);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            daoWrapper = new DaoWrapper(new Config(config.workingDir(), MEMORY_LIMIT_BYTES));
            requestHandler.setDaoWrapper(daoWrapper);
            router = new RendezvousRouter(config.clusterUrls());
            server = new HttpServerImpl(createServerConfig(config.selfPort()));
            server.addRequestHandlers(this);
            server.start();
            LOGGER.debug("Server started: " + config.selfUrl());
        } catch (IOException e) {
            LOGGER.error("Error occurred while starting the server");
            throw new IOException(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        try {
            daoWrapper.stop();
        } catch (IOException e) {
            LOGGER.error("Error occurred while closing database");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Path(DEFAULT_PATH)
    public void entity(Request request, HttpSession session,
                       @Param(value = "id", required = true) String id,
                       @Param(value = "ack") Integer ackNumber,
                       @Param(value = "from") Integer fromNumber) throws TimeoutException, IOException {
        if (id == null || id.isBlank()) {
            session.sendResponse(badIdResponse);
            return;
        }

        if (handleTimestampHeader(request, session, id)) {
            return;
        }

        int from = (fromNumber == null || fromNumber < 0) ? config.clusterUrls().size() : fromNumber;
        int ack = (ackNumber == null || ackNumber < 0) ? calculateAck(from) : ackNumber;

        if (ack > from || ack == 0) {
            session.sendResponse(wrongAckResponse);
            return;
        }

        List<Node> targetNodes = router.getNodes(id, from);
        dispatchRequestsToNodes(request, session, targetNodes, id, ack, from);
    }

    private boolean handleTimestampHeader(Request request, HttpSession session, String id) throws IOException {
        String timestamp = request.getHeader(TIMESTAMP_HEADER + ": ");
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        try {
            long parsedTimestamp = Long.parseLong(timestamp);
            session.sendResponse(requestHandler.handleRequestLocally(request, id, parsedTimestamp));
        } catch (NumberFormatException e) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
        return true;
    }

    private void dispatchRequestsToNodes(
            Request request,
            HttpSession session,
            List<Node> targetNodes,
            String id,
            Integer ack,
            Integer from
    ) {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicLong responseTime = new AtomicLong();
        AtomicReference<Response> latestResponse = new AtomicReference<>();
        AtomicBoolean responseSent = new AtomicBoolean();

        for (Node node : targetNodes) {
            if (!node.isAlive()) {
                LOGGER.info("node is unreachable: " + node.address);
            }
            long timestamp = System.currentTimeMillis();
            CompletableFuture<Response> futureResponse =
                    requestHandler.processRequest(request, id, node, timestamp, config.selfUrl());

            futureResponse
                    .whenCompleteAsync((resp, ex) -> {
                        if (resp == null) {
                            unableToRespondCheck(session, failures, ack, from);
                        } else {
                            updateLatestResponse(resp, request.getMethod(), responseTime, latestResponse);
                            if (canEarlyResponse(resp, successes, ack, responseSent)) {
                                requestHandler.sendResponse(session, latestResponse.get(), failures);
                            }
                        }
                    }, responseExecutor)
                    .exceptionally(ex -> {
                        logFailure(ex.getMessage(), failures);
                        return null;
                    });

        }
    }

    private boolean canEarlyResponse(Response resp, AtomicInteger successes, Integer ack, AtomicBoolean responseSent) {
        return responseIsGood(resp)
                && successes.incrementAndGet() >= ack
                && !responseSent.getAndSet(true);
    }

    private void unableToRespondCheck(HttpSession session, AtomicInteger failures, Integer ack, Integer from) {
        if (failures.incrementAndGet() > from - ack) {
            String message = "Not enough replicas responded";
            LOGGER.info(message);
            try {
                byte[] mes = message.getBytes(StandardCharsets.UTF_8);
                session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, mes));
            } catch (IOException e) {
                logFailure(e.getMessage(), failures);
            }
        }
    }

    private boolean responseIsGood(Response response) {
        // accepting all responses we can get from handleRequestLocally()
        return response.getStatus() <= HttpURLConnection.HTTP_PARTIAL
                || response.getStatus() == HttpURLConnection.HTTP_NOT_FOUND
                || response.getStatus() == HttpURLConnection.HTTP_BAD_METHOD;
    }

    private void logFailure(String e, AtomicInteger failures) {
        failures.incrementAndGet();
        LOGGER.error(e);
    }

    private long extractTimestampFromResponse(Response response) {
        String timestamp = response.getHeaders()[2];
        if (timestamp == null) {
            return -1;
        }
        String timestampValue = timestamp.substring(TIMESTAMP_HEADER.length() + 2).trim();
        return Long.parseLong(timestampValue);
    }

    private void updateLatestResponse(
            Response response,
            int method,
            AtomicLong responseTime,
            AtomicReference<Response> latestResponse
    ) {
        latestResponse.compareAndSet(null, response);
        if (method == Request.METHOD_GET) {
            long timestamp = extractTimestampFromResponse(response);
            long currentResponseTime;
            do {
                currentResponseTime = responseTime.get();
                if (timestamp <= currentResponseTime) {
                    return;
                }
            } while (!responseTime.compareAndSet(currentResponseTime, timestamp));
        }
        latestResponse.set(response);
    }

    private static HttpServerConfig createServerConfig(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static int calculateAck(int clusterSize) {
        if (clusterSize <= 2) {
            return clusterSize;
        }
        return (int) floor(clusterSize * 0.75);
    }

    @ServiceFactory(stage = 6)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

}
