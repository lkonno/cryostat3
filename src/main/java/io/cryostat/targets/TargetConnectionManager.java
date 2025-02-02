/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.targets;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.rmi.ConnectIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import javax.management.remote.JMXServiceURL;
import javax.naming.ServiceUnavailableException;
import javax.security.sasl.SaslException;

import org.openjdk.jmc.rjmx.ConnectionException;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.credentials.Credential;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.targets.Target.EventKind;
import io.cryostat.targets.Target.TargetDiscovery;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;

@ApplicationScoped
public class TargetConnectionManager {

    private final JFRConnectionToolkit jfrConnectionToolkit;
    private final MatchExpressionEvaluator matchExpressionEvaluator;
    private final AgentConnectionFactory agentConnectionFactory;
    private final Executor executor;
    private final Logger logger;

    private final AsyncLoadingCache<URI, JFRConnection> connections;
    private final Map<URI, Object> targetLocks;
    private final Optional<Semaphore> semaphore;

    @Inject
    @SuppressFBWarnings(
            value = "CT_CONSTRUCTOR_THROW",
            justification =
                    "Caffeine cache is initialized here, so it will not throw due to"
                            + " reconfiguration")
    TargetConnectionManager(
            JFRConnectionToolkit jfrConnectionToolkit,
            MatchExpressionEvaluator matchExpressionEvaluator,
            AgentConnectionFactory agentConnectionFactory,
            Executor executor,
            Logger logger) {
        FlightRecorder.register(TargetConnectionOpened.class);
        FlightRecorder.register(TargetConnectionClosed.class);
        this.jfrConnectionToolkit = jfrConnectionToolkit;
        this.matchExpressionEvaluator = matchExpressionEvaluator;
        this.agentConnectionFactory = agentConnectionFactory;
        this.executor = executor;

        int maxTargetConnections = 0; // TODO make configurable

        this.targetLocks = new ConcurrentHashMap<>();
        if (maxTargetConnections > 0) {
            this.semaphore = Optional.of(new Semaphore(maxTargetConnections, true));
        } else {
            this.semaphore = Optional.empty();
        }

        Caffeine<URI, JFRConnection> cacheBuilder =
                Caffeine.newBuilder()
                        .executor(executor)
                        .scheduler(Scheduler.systemScheduler())
                        .removalListener(this::closeConnection);
        Duration ttl = Duration.ofSeconds(10); // TODO make configurable
        if (ttl.isZero() || ttl.isNegative()) {
            logger.errorv(
                    "TTL must be a positive integer in seconds, was {0} - ignoring",
                    ttl.toSeconds());
        } else {
            cacheBuilder = cacheBuilder.expireAfterAccess(ttl);
        }
        this.connections = cacheBuilder.buildAsync(new ConnectionLoader());
        this.logger = logger;
    }

    @ConsumeEvent(Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) {
        // force removal of connections from cache when we're notified about targets being lost.
        // This should already be taken care of by the connection close listener, but this provides
        // some additional insurance in case a target disappears and the underlying JMX network
        // connection doesn't immediately report itself as closed
        if (EventKind.LOST.equals(event.kind())) {
            for (URI uri : connections.asMap().keySet()) {
                if (Objects.equals(uri, event.serviceRef().connectUrl)) {
                    connections.synchronous().invalidate(uri);
                }
            }
        }
    }

    @Blocking
    @ConsumeEvent(Credential.CREDENTIALS_STORED)
    void onCredentialsStored(Credential credential) {
        handleCredentialChange(credential);
    }

    @Blocking
    @ConsumeEvent(Credential.CREDENTIALS_UPDATED)
    void onCredentialsUpdated(Credential credential) {
        handleCredentialChange(credential);
    }

    @Blocking
    @ConsumeEvent(Credential.CREDENTIALS_DELETED)
    void onCredentialsDeleted(Credential credential) {
        handleCredentialChange(credential);
    }

    @Blocking
    void handleCredentialChange(Credential credential) {
        for (var entry : connections.asMap().entrySet()) {
            URI key = entry.getKey();
            var target = Target.find("connectUrl", key).<Target>firstResultOptional();
            if (target.isEmpty()) {
                continue;
            }
            try {
                if (matchExpressionEvaluator.applies(credential.matchExpression, target.get())) {
                    connections.synchronous().invalidate(key);
                }
            } catch (ScriptException se) {
                logger.warn(se);
            }
        }
    }

    public <T> Uni<T> executeConnectedTaskAsync(Target target, ConnectedTask<T> task) {
        return Uni.createFrom()
                .completionStage(
                        connections
                                .get(target.connectUrl)
                                .thenApplyAsync(
                                        conn -> {
                                            try {
                                                synchronized (
                                                        targetLocks.computeIfAbsent(
                                                                target.connectUrl,
                                                                k -> new Object())) {
                                                    return task.execute(conn);
                                                }
                                            } catch (Exception e) {
                                                logger.error("Connection failure", e);
                                                throw new CompletionException(e);
                                            }
                                        },
                                        executor));
    }

    @Blocking
    public <T> T executeConnectedTask(Target target, ConnectedTask<T> task) throws Exception {
        synchronized (targetLocks.computeIfAbsent(target.connectUrl, k -> new Object())) {
            return task.execute(connections.get(target.connectUrl).get());
        }
    }

    @Blocking
    public <T> T executeDirect(
            Target target, Optional<Credential> credentials, ConnectedTask<T> task)
            throws Exception {
        try (var conn = connect(target.connectUrl, credentials)) {
            return task.execute(conn);
        }
    }

    /**
     * Mark a connection as still in use by the consumer. Connections expire from cache and are
     * automatically closed after {@link NetworkModule.TARGET_CACHE_TTL}. For long-running
     * operations which may hold the connection open and active for longer than the configured TTL,
     * this method provides a way for the consumer to inform the {@link TargetConnectionManager} and
     * its internal cache that the connection is in fact still active and should not be
     * expired/closed. This will extend the lifetime of the cache entry by another TTL into the
     * future from the time this method is called. This may be done repeatedly as long as the
     * connection is required to remain active.
     *
     * @return false if the connection for the specified {@link Target} was already removed from
     *     cache, true if it is still active and was refreshed
     */
    public boolean markConnectionInUse(Target target) {
        return connections.getIfPresent(target.connectUrl) != null;
    }

    private void closeConnection(URI connectUrl, JFRConnection connection, RemovalCause cause) {
        if (connectUrl == null) {
            logger.error("Connection eviction triggered with null connectUrl");
            return;
        }
        if (connection == null) {
            logger.error("Connection eviction triggered with null connection");
            return;
        }
        try {
            TargetConnectionClosed evt = new TargetConnectionClosed(connectUrl, cause.name());
            logger.infov("Removing cached connection for {0}: {1}", connectUrl, cause);
            evt.begin();
            try {
                connection.close();
                targetLocks.remove(connectUrl);
            } catch (RuntimeException e) {
                evt.setExceptionThrown(true);
                throw e;
            } finally {
                evt.end();
                if (evt.shouldCommit()) {
                    evt.commit();
                }
            }
        } catch (Exception e) {
            logger.error("Connection eviction failed", e);
        } finally {
            if (semaphore.isPresent()) {
                semaphore.get().release();
                logger.tracev(
                        "Semaphore released! Permits: {0}", semaphore.get().availablePermits());
            }
        }
    }

    @Transactional
    JFRConnection connect(URI connectUrl) throws Exception {
        var credentials =
                Target.find("connectUrl", connectUrl)
                        .<Target>firstResultOptional()
                        .map(
                                t ->
                                        Credential.<Credential>listAll().stream()
                                                .filter(
                                                        c -> {
                                                            try {
                                                                return matchExpressionEvaluator
                                                                        .applies(
                                                                                c.matchExpression,
                                                                                t);
                                                            } catch (ScriptException e) {
                                                                logger.error(e);
                                                                return false;
                                                            }
                                                        })
                                                .findFirst()
                                                .orElse(null));
        return connect(connectUrl, credentials);
    }

    @Blocking
    JFRConnection connect(URI connectUrl, Optional<Credential> credentials) throws Exception {
        TargetConnectionOpened evt = new TargetConnectionOpened(connectUrl.toString());
        evt.begin();
        try {
            if (semaphore.isPresent()) {
                semaphore.get().acquire();
            }

            if (Set.of("http", "https", "cryostat-agent").contains(connectUrl.getScheme())) {
                return agentConnectionFactory.createConnection(connectUrl);
            }

            return jfrConnectionToolkit.connect(
                    new JMXServiceURL(connectUrl.toString()),
                    credentials
                            .map(c -> new io.cryostat.core.net.Credentials(c.username, c.password))
                            .orElse(null),
                    Collections.singletonList(
                            () -> connections.synchronous().invalidate(connectUrl)));
        } catch (Exception e) {
            evt.setExceptionThrown(true);
            if (semaphore.isPresent()) {
                semaphore.get().release();
            }
            throw e;
        } finally {
            evt.end();
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }
    }

    private class ConnectionLoader implements AsyncCacheLoader<URI, JFRConnection> {

        @Override
        public CompletableFuture<JFRConnection> asyncLoad(URI key, Executor executor)
                throws Exception {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            logger.infov("Opening connection to {0}", key);
                            return connect(key);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    },
                    executor);
        }

        @Override
        public CompletableFuture<JFRConnection> asyncReload(
                URI key, JFRConnection prev, Executor executor) throws Exception {
            // if we're refreshed and already have an existing, open connection, just reuse it.
            if (prev.isConnected()) {
                logger.infov("Reusing connection to {0}", key);
                return CompletableFuture.completedFuture(prev);
            }
            logger.infov("Refreshing connection to {0}", key);
            return asyncLoad(key, executor);
        }
    }

    public interface ConnectedTask<T> {
        T execute(JFRConnection connection) throws Exception;
    }

    public static boolean isTargetConnectionFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectionException.class) >= 0
                || ExceptionUtils.indexOfType(e, FlightRecorderException.class) >= 0;
    }

    public static boolean isJmxAuthFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, SecurityException.class) >= 0
                || ExceptionUtils.indexOfType(e, SaslException.class) >= 0;
    }

    public static boolean isJmxSslFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectIOException.class) >= 0
                && !isServiceTypeFailure(e);
    }

    /** Check if the exception happened because the port connected to a non-JMX service. */
    public static boolean isServiceTypeFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, ConnectIOException.class) >= 0
                && ExceptionUtils.indexOfType(e, SocketTimeoutException.class) >= 0;
    }

    public static boolean isUnknownTargetFailure(Exception e) {
        return ExceptionUtils.indexOfType(e, java.net.UnknownHostException.class) >= 0
                || ExceptionUtils.indexOfType(e, java.rmi.UnknownHostException.class) >= 0
                || ExceptionUtils.indexOfType(e, ServiceUnavailableException.class) >= 0;
    }

    @Name("io.cryostat.net.TargetConnectionManager.TargetConnectionOpened")
    @Label("Target Connection Opened")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class TargetConnectionOpened extends Event {
        String serviceUri;
        boolean exceptionThrown;

        TargetConnectionOpened(String serviceUri) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }

    @Name("io.cryostat.net.TargetConnectionManager.TargetConnectionClosed")
    @Label("Target Connection Closed")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class TargetConnectionClosed extends Event {
        URI serviceUri;
        boolean exceptionThrown;
        String reason;

        TargetConnectionClosed(URI serviceUri, String reason) {
            this.serviceUri = serviceUri;
            this.exceptionThrown = false;
            this.reason = reason;
        }

        void setExceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
        }
    }
}
