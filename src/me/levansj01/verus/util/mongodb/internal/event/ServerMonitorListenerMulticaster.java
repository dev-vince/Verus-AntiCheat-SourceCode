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

package me.levansj01.verus.util.mongodb.internal.event;

import java.util.ArrayList;
import java.util.List;

import me.levansj01.verus.util.mongodb.diagnostics.logging.Logger;
import me.levansj01.verus.util.mongodb.diagnostics.logging.Loggers;
import me.levansj01.verus.util.mongodb.event.ServerHeartbeatFailedEvent;
import me.levansj01.verus.util.mongodb.event.ServerHeartbeatStartedEvent;
import me.levansj01.verus.util.mongodb.event.ServerHeartbeatSucceededEvent;
import me.levansj01.verus.util.mongodb.event.ServerMonitorListener;

import static java.lang.String.format;
import static me.levansj01.verus.util.mongodb.assertions.Assertions.isTrue;

final class ServerMonitorListenerMulticaster implements ServerMonitorListener {
    private static final Logger LOGGER = Loggers.getLogger("cluster.event");

    private final List<ServerMonitorListener> serverMonitorListeners;

    ServerMonitorListenerMulticaster(final List<ServerMonitorListener> serverMonitorListeners) {
        isTrue("All ServerMonitorListener instances are non-null", !serverMonitorListeners.contains(null));
        this.serverMonitorListeners = new ArrayList<ServerMonitorListener>(serverMonitorListeners);
    }

    @Override
    public void serverHearbeatStarted(final ServerHeartbeatStartedEvent event) {
        for (ServerMonitorListener cur : serverMonitorListeners) {
            try {
                cur.serverHearbeatStarted(event);
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(format("Exception thrown raising server heartbeat started event to listener %s", cur), e);
                }
            }
        }
    }

    @Override
    public void serverHeartbeatSucceeded(final ServerHeartbeatSucceededEvent event) {
        for (ServerMonitorListener cur : serverMonitorListeners) {
            try {
                cur.serverHeartbeatSucceeded(event);
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(format("Exception thrown raising server heartbeat succeeded event to listener %s", cur), e);
                }
            }
        }
    }

    @Override
    public void serverHeartbeatFailed(final ServerHeartbeatFailedEvent event) {
        for (ServerMonitorListener cur : serverMonitorListeners) {
            try {
                cur.serverHeartbeatFailed(event);
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(format("Exception thrown raising server heartbeat failed event to listener %s", cur), e);
                }
            }
        }
    }
}
