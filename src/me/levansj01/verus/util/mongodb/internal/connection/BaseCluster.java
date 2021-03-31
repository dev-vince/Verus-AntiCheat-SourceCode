/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.levansj01.verus.util.mongodb.internal.connection;

import org.bson.BsonTimestamp;

import me.levansj01.verus.util.mongodb.MongoClientException;
import me.levansj01.verus.util.mongodb.MongoIncompatibleDriverException;
import me.levansj01.verus.util.mongodb.MongoInterruptedException;
import me.levansj01.verus.util.mongodb.MongoTimeoutException;
import me.levansj01.verus.util.mongodb.ServerAddress;
import me.levansj01.verus.util.mongodb.connection.ClusterDescription;
import me.levansj01.verus.util.mongodb.connection.ClusterId;
import me.levansj01.verus.util.mongodb.connection.ClusterSettings;
import me.levansj01.verus.util.mongodb.connection.ClusterType;
import me.levansj01.verus.util.mongodb.connection.ServerDescription;
import me.levansj01.verus.util.mongodb.diagnostics.logging.Logger;
import me.levansj01.verus.util.mongodb.diagnostics.logging.Loggers;
import me.levansj01.verus.util.mongodb.event.ClusterClosedEvent;
import me.levansj01.verus.util.mongodb.event.ClusterDescriptionChangedEvent;
import me.levansj01.verus.util.mongodb.event.ClusterListener;
import me.levansj01.verus.util.mongodb.event.ClusterOpeningEvent;
import me.levansj01.verus.util.mongodb.internal.async.SingleResultCallback;
import me.levansj01.verus.util.mongodb.selector.CompositeServerSelector;
import me.levansj01.verus.util.mongodb.selector.ServerSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static me.levansj01.verus.util.mongodb.assertions.Assertions.isTrue;
import static me.levansj01.verus.util.mongodb.assertions.Assertions.notNull;
import static me.levansj01.verus.util.mongodb.connection.ServerDescription.MAX_DRIVER_WIRE_VERSION;
import static me.levansj01.verus.util.mongodb.connection.ServerDescription.MIN_DRIVER_SERVER_VERSION;
import static me.levansj01.verus.util.mongodb.connection.ServerDescription.MIN_DRIVER_WIRE_VERSION;
import static me.levansj01.verus.util.mongodb.internal.connection.EventHelper.wouldDescriptionsGenerateEquivalentEvents;
import static me.levansj01.verus.util.mongodb.internal.event.EventListenerHelper.createServerListener;
import static me.levansj01.verus.util.mongodb.internal.event.EventListenerHelper.getClusterListener;

abstract class BaseCluster implements Cluster {

    private static final Logger LOGGER = Loggers.getLogger("cluster");

    private final AtomicReference<CountDownLatch> phase = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
    private final ClusterableServerFactory serverFactory;
    private final ThreadLocal<Random> random = new ThreadLocal<Random>();
    private final ClusterId clusterId;
    private final ClusterSettings settings;
    private final ClusterListener clusterListener;
    private final Deque<ServerSelectionRequest> waitQueue = new ConcurrentLinkedDeque<ServerSelectionRequest>();
    private final ClusterClock clusterClock = new ClusterClock();
    private Thread waitQueueHandler;

    private volatile boolean isClosed;
    private volatile ClusterDescription description;

    BaseCluster(final ClusterId clusterId, final ClusterSettings settings, final ClusterableServerFactory serverFactory) {
        this.clusterId = notNull("clusterId", clusterId);
        this.settings = notNull("settings", settings);
        this.serverFactory = notNull("serverFactory", serverFactory);
        this.clusterListener = getClusterListener(settings);
        clusterListener.clusterOpening(new ClusterOpeningEvent(clusterId));
        description = new ClusterDescription(settings.getMode(), ClusterType.UNKNOWN, Collections.<ServerDescription>emptyList(),
                settings, serverFactory.getSettings());
    }

    @Override
    public BsonTimestamp getClusterTime() {
        return clusterClock.getClusterTime();
    }

    @Override
    public ServerTuple selectServer(final ServerSelector serverSelector) {
        isTrue("open", !isClosed());

        try {
            CountDownLatch currentPhase = phase.get();
            ClusterDescription curDescription = description;
            ServerSelector compositeServerSelector = getCompositeServerSelector(serverSelector);
            ServerTuple serverTuple = selectRandomServer(compositeServerSelector, curDescription);

            boolean selectionFailureLogged = false;

            long startTimeNanos = System.nanoTime();
            long curTimeNanos = startTimeNanos;
            long maxWaitTimeNanos = getMaxWaitTimeNanos();

            while (true) {
                throwIfIncompatible(curDescription);

                if (serverTuple != null) {
                    return serverTuple;
                }

                if (curTimeNanos - startTimeNanos > maxWaitTimeNanos) {
                    throw createTimeoutException(serverSelector, curDescription);
                }

                if (!selectionFailureLogged) {
                    logServerSelectionFailure(serverSelector, curDescription);
                    selectionFailureLogged = true;
                }

                connect();

                currentPhase.await(Math.min(maxWaitTimeNanos - (curTimeNanos - startTimeNanos), getMinWaitTimeNanos()), NANOSECONDS);

                curTimeNanos = System.nanoTime();

                currentPhase = phase.get();
                curDescription = description;
                serverTuple = selectRandomServer(compositeServerSelector, curDescription);
            }

        } catch (InterruptedException e) {
            throw new MongoInterruptedException(format("Interrupted while waiting for a server that matches %s", serverSelector), e);
        }
    }

    @Override
    public void selectServerAsync(final ServerSelector serverSelector, final SingleResultCallback<ServerTuple> callback) {
        isTrue("open", !isClosed());

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(format("Asynchronously selecting server with selector %s", serverSelector));
        }
        ServerSelectionRequest request = new ServerSelectionRequest(serverSelector, getCompositeServerSelector(serverSelector),
                                                                    getMaxWaitTimeNanos(), callback);

        CountDownLatch currentPhase = phase.get();
        ClusterDescription currentDescription = description;

        if (!handleServerSelectionRequest(request, currentPhase, currentDescription)) {
            notifyWaitQueueHandler(request);
        }
    }

    @Override
    public ClusterDescription getDescription() {
        isTrue("open", !isClosed());

        try {
            CountDownLatch currentPhase = phase.get();
            ClusterDescription curDescription = description;

            boolean selectionFailureLogged = false;

            long startTimeNanos = System.nanoTime();
            long curTimeNanos = startTimeNanos;
            long maxWaitTimeNanos = getMaxWaitTimeNanos();

            while (curDescription.getType() == ClusterType.UNKNOWN) {

                if (curTimeNanos - startTimeNanos > maxWaitTimeNanos) {
                    throw new MongoTimeoutException(format("Timed out after %d ms while waiting to connect. Client view of cluster state "
                                                           + "is %s",
                                                           settings.getServerSelectionTimeout(MILLISECONDS),
                                                           curDescription.getShortDescription()));
                }

                if (!selectionFailureLogged) {
                    if (LOGGER.isInfoEnabled()) {
                        if (settings.getServerSelectionTimeout(MILLISECONDS) < 0) {
                            LOGGER.info("Cluster description not yet available. Waiting indefinitely.");
                        } else {
                            LOGGER.info(format("Cluster description not yet available. Waiting for %d ms before timing out",
                                               settings.getServerSelectionTimeout(MILLISECONDS)));
                        }
                    }
                    selectionFailureLogged = true;
                }

                connect();

                currentPhase.await(Math.min(maxWaitTimeNanos - (curTimeNanos - startTimeNanos), getMinWaitTimeNanos()), NANOSECONDS);

                curTimeNanos = System.nanoTime();

                currentPhase = phase.get();
                curDescription = description;
            }
            return curDescription;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException("Interrupted while waiting to connect", e);
        }
    }

    protected ClusterId getClusterId() {
        return clusterId;
    }

    public ClusterSettings getSettings() {
        return settings;
    }

    public ClusterableServerFactory getServerFactory() {
        return serverFactory;
    }

    protected abstract void connect();

    @Override
    public void close() {
        if (!isClosed()) {
            isClosed = true;
            phase.get().countDown();
            clusterListener.clusterClosed(new ClusterClosedEvent(clusterId));
            stopWaitQueueHandler();
        }
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    /**
     * Return the server at the given address.
     *
     * @param serverAddress the address
     * @return the server, or null if the cluster no longer contains a server at this address.
     */
    protected abstract ClusterableServer getServer(ServerAddress serverAddress);

    protected synchronized void updateDescription(final ClusterDescription newDescription) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(format("Updating cluster description to  %s", newDescription.getShortDescription()));
        }

        description = newDescription;
        updatePhase();
    }

    protected void fireChangeEvent(final ClusterDescription newDescription, final ClusterDescription previousDescription) {
        if (!wouldDescriptionsGenerateEquivalentEvents(newDescription, previousDescription)) {
             clusterListener.clusterDescriptionChanged(
                     new ClusterDescriptionChangedEvent(getClusterId(), newDescription, previousDescription));
        }
    }

    @Override
    public ClusterDescription getCurrentDescription() {
        return description;
    }

    private synchronized void updatePhase() {
        phase.getAndSet(new CountDownLatch(1)).countDown();
    }

    private long getMaxWaitTimeNanos() {
        if (settings.getServerSelectionTimeout(NANOSECONDS) < 0) {
            return Long.MAX_VALUE;
        }
        return settings.getServerSelectionTimeout(NANOSECONDS);
    }

    private long getMinWaitTimeNanos() {
        return serverFactory.getSettings().getMinHeartbeatFrequency(NANOSECONDS);
    }

    private boolean handleServerSelectionRequest(final ServerSelectionRequest request, final CountDownLatch currentPhase,
                                                 final ClusterDescription description) {
        try {
            if (currentPhase != request.phase) {
                CountDownLatch prevPhase = request.phase;
                request.phase = currentPhase;
                if (!description.isCompatibleWithDriver()) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Asynchronously failed server selection due to driver incompatibility with server");
                    }
                    request.onResult(null, createIncompatibleException(description));
                    return true;
                }

                ServerTuple serverTuple = selectRandomServer(request.compositeSelector, description);
                if (serverTuple != null) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace(format("Asynchronously selected server %s", serverTuple.getServerDescription().getAddress()));
                    }
                    request.onResult(serverTuple, null);
                    return true;
                }
                if (prevPhase == null) {
                    logServerSelectionFailure(request.originalSelector, description);
                }
            }

            if (request.timedOut()) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Asynchronously failed server selection after timeout");
                }
                request.onResult(null, createTimeoutException(request.originalSelector, description));
                return true;
            }

            return false;
        } catch (Exception e) {
            request.onResult(null, e);
            return true;
        }
    }

    private void logServerSelectionFailure(final ServerSelector serverSelector, final ClusterDescription curDescription) {
        if (LOGGER.isInfoEnabled()) {
            if (settings.getServerSelectionTimeout(MILLISECONDS) < 0) {
                LOGGER.info(format("No server chosen by %s from cluster description %s. Waiting indefinitely.",
                                   serverSelector, curDescription));
            } else {
                LOGGER.info(format("No server chosen by %s from cluster description %s. Waiting for %d ms before timing out",
                                   serverSelector, curDescription, settings.getServerSelectionTimeout(MILLISECONDS)));
            }
        }
    }


    private ServerTuple selectRandomServer(final ServerSelector serverSelector, final ClusterDescription clusterDescription) {
        List<ServerDescription> serverDescriptions = serverSelector.select(clusterDescription);
        if (!serverDescriptions.isEmpty()) {
            return getRandomServer(new ArrayList<ServerDescription>(serverDescriptions));
        } else {
            return null;
        }
    }

    private ServerSelector getCompositeServerSelector(final ServerSelector serverSelector) {
        if (settings.getServerSelector() == null) {
            return serverSelector;
        } else {
            return new CompositeServerSelector(asList(serverSelector, settings.getServerSelector()));
        }
    }

    // gets a random server that still exists in the cluster.  Returns null if there are none.
    private ServerTuple getRandomServer(final List<ServerDescription> serverDescriptions) {
        while (!serverDescriptions.isEmpty()) {
            int serverPos = getRandom().nextInt(serverDescriptions.size());
            ServerDescription serverDescription = serverDescriptions.get(serverPos);
            Server server = getServer(serverDescription.getAddress());
            if (server != null) {
                return new ServerTuple(server, serverDescription);
            } else {
                serverDescriptions.remove(serverPos);
            }
        }
        return null;
    }

    // it's important that Random instances are created in this way instead of via subclassing ThreadLocal and overriding the
    // initialValue() method.
    private Random getRandom() {
        Random result = random.get();
        if (result == null) {
            result = new Random();
            random.set(result);
        }
        return result;
    }

    protected ClusterableServer createServer(final ServerAddress serverAddress,
                                             final ServerDescriptionChangedListener serverDescriptionChangedListener) {
        return serverFactory.create(serverAddress, serverDescriptionChangedListener, createServerListener(serverFactory.getSettings()),
                clusterClock);
    }

    private void throwIfIncompatible(final ClusterDescription curDescription) {
        if (!curDescription.isCompatibleWithDriver()) {
            throw createIncompatibleException(curDescription);
        }
    }

    private MongoIncompatibleDriverException createIncompatibleException(final ClusterDescription curDescription) {
        String message;
        ServerDescription incompatibleServer = curDescription.findServerIncompatiblyOlderThanDriver();
        if (incompatibleServer != null) {
            message = format("Server at %s reports wire version %d, but this version of the driver requires at least %d (MongoDB %s).",
                    incompatibleServer.getAddress(), incompatibleServer.getMaxWireVersion(),
                    MIN_DRIVER_WIRE_VERSION, MIN_DRIVER_SERVER_VERSION);
        } else {
            incompatibleServer = curDescription.findServerIncompatiblyNewerThanDriver();
            if (incompatibleServer != null) {
                message = format("Server at %s requires wire version %d, but this version of the driver only supports up to %d.",
                        incompatibleServer.getAddress(), incompatibleServer.getMinWireVersion(), MAX_DRIVER_WIRE_VERSION);
            } else {
                throw new IllegalStateException("Server can't be both older than the driver and newer.");
            }
        }
        return new MongoIncompatibleDriverException(message, curDescription);
    }

    private MongoTimeoutException createTimeoutException(final ServerSelector serverSelector, final ClusterDescription curDescription) {
        return new MongoTimeoutException(format("Timed out after %d ms while waiting for a server that matches %s. "
                                                + "Client view of cluster state is %s",
                                                settings.getServerSelectionTimeout(MILLISECONDS), serverSelector,
                                                curDescription.getShortDescription()));
    }

    private static final class ServerSelectionRequest {
        private final ServerSelector originalSelector;
        private final ServerSelector compositeSelector;
        private final long maxWaitTimeNanos;
        private final SingleResultCallback<ServerTuple> callback;
        private final long startTimeNanos = System.nanoTime();
        private CountDownLatch phase;

        ServerSelectionRequest(final ServerSelector serverSelector, final ServerSelector compositeSelector,
                               final long maxWaitTimeNanos,
                               final SingleResultCallback<ServerTuple> callback) {
            this.originalSelector = serverSelector;
            this.compositeSelector = compositeSelector;
            this.maxWaitTimeNanos = maxWaitTimeNanos;
            this.callback = callback;
        }

        void onResult(final ServerTuple serverTuple, final Throwable t) {
            try {
                callback.onResult(serverTuple, t);
            } catch (Throwable tr) {
                // ignore
            }
        }

        boolean timedOut() {
            return System.nanoTime() - startTimeNanos > maxWaitTimeNanos;
        }

        long getRemainingTime() {
            return startTimeNanos + maxWaitTimeNanos - System.nanoTime();
        }
    }

    private synchronized void notifyWaitQueueHandler(final ServerSelectionRequest request) {
        if (isClosed) {
            return;
        }

        waitQueue.add(request);

        if (waitQueueHandler == null) {
            waitQueueHandler = new Thread(new WaitQueueHandler(), "cluster-" + clusterId.getValue());
            waitQueueHandler.setDaemon(true);
            waitQueueHandler.start();
        } else {
            updatePhase();
        }
    }

    private synchronized void stopWaitQueueHandler() {
        if (waitQueueHandler != null) {
            waitQueueHandler.interrupt();
        }
    }

    private final class WaitQueueHandler implements Runnable {
        public void run() {
            while (!isClosed) {
                CountDownLatch currentPhase = phase.get();
                ClusterDescription curDescription = description;
                long waitTimeNanos = Long.MAX_VALUE;

                for (Iterator<ServerSelectionRequest> iter = waitQueue.iterator(); iter.hasNext();) {
                    ServerSelectionRequest nextRequest = iter.next();
                    if (handleServerSelectionRequest(nextRequest, currentPhase, curDescription)) {
                        iter.remove();
                    } else {
                        waitTimeNanos = Math.min(nextRequest.getRemainingTime(), Math.min(getMinWaitTimeNanos(), waitTimeNanos));
                    }
                }

                // if there are any waiters that were not satisfied, connect
                if (waitTimeNanos < Long.MAX_VALUE) {
                    connect();
                }

                try {
                    currentPhase.await(waitTimeNanos, NANOSECONDS);
                } catch (InterruptedException e) {
                    // The cluster has been closed and the while loop will exit.
                }
            }
            // Notify all remaining waiters that a shutdown is in progress
            for (Iterator<ServerSelectionRequest> iter = waitQueue.iterator(); iter.hasNext();) {
                iter.next().onResult(null, new MongoClientException("Shutdown in progress"));
                iter.remove();
            }
        }
    }
}
