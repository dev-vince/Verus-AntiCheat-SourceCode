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

package me.levansj01.verus.util.mongodb.internal.binding;

import static me.levansj01.verus.util.mongodb.assertions.Assertions.notNull;

import me.levansj01.verus.util.mongodb.ReadConcern;
import me.levansj01.verus.util.mongodb.ReadPreference;
import me.levansj01.verus.util.mongodb.ServerAddress;
import me.levansj01.verus.util.mongodb.ServerApi;
import me.levansj01.verus.util.mongodb.connection.ServerDescription;
import me.levansj01.verus.util.mongodb.internal.connection.Cluster;
import me.levansj01.verus.util.mongodb.internal.connection.Connection;
import me.levansj01.verus.util.mongodb.internal.connection.ReadConcernAwareNoOpSessionContext;
import me.levansj01.verus.util.mongodb.internal.connection.Server;
import me.levansj01.verus.util.mongodb.internal.connection.ServerTuple;
import me.levansj01.verus.util.mongodb.internal.selector.ReadPreferenceServerSelector;
import me.levansj01.verus.util.mongodb.internal.selector.ServerAddressSelector;
import me.levansj01.verus.util.mongodb.internal.selector.WritableServerSelector;
import me.levansj01.verus.util.mongodb.internal.session.SessionContext;
import me.levansj01.verus.util.mongodb.lang.Nullable;
import me.levansj01.verus.util.mongodb.selector.ServerSelector;

/**
 * A simple ReadWriteBinding implementation that supplies write connection sources bound to a possibly different primary each time, and a
 * read connection source bound to a possible different server each time.
 *
 * @since 3.0
 */
public class ClusterBinding extends AbstractReferenceCounted implements ClusterAwareReadWriteBinding {
    private final Cluster cluster;
    private final ReadPreference readPreference;
    private final ReadConcern readConcern;
    @Nullable
    private final ServerApi serverApi;

    /**
     * Creates an instance.
     * @param cluster        a non-null Cluster which will be used to select a server to bind to
     * @param readPreference a non-null ReadPreference for read operations
     * @param readConcern    a non-null read concern
     * @param serverApi      a server API, which may be null
     * @since 3.8
     */
    public ClusterBinding(final Cluster cluster, final ReadPreference readPreference, final ReadConcern readConcern,
                          final @Nullable ServerApi serverApi) {
        this.cluster = notNull("cluster", cluster);
        this.readPreference = notNull("readPreference", readPreference);
        this.readConcern = notNull("readConcern", readConcern);
        this.serverApi = serverApi;
    }

    /**
     * Return the cluster.
     * @return the cluster
     * @since 3.11
     */
    public Cluster getCluster() {
        return cluster;
    }

    @Override
    public ReadWriteBinding retain() {
        super.retain();
        return this;
    }

    @Override
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    @Override
    public SessionContext getSessionContext() {
        return new ReadConcernAwareNoOpSessionContext(readConcern);
    }

    @Override
    @Nullable
    public ServerApi getServerApi() {
        return serverApi;
    }

    @Override
    public ConnectionSource getReadConnectionSource() {
        return new ClusterBindingConnectionSource(new ReadPreferenceServerSelector(readPreference));
    }

    @Override
    public ConnectionSource getWriteConnectionSource() {
        return new ClusterBindingConnectionSource(new WritableServerSelector());
    }

    @Override
    public ConnectionSource getConnectionSource(final ServerAddress serverAddress) {
        return new ClusterBindingConnectionSource(new ServerAddressSelector(serverAddress));
    }

    private final class ClusterBindingConnectionSource extends AbstractReferenceCounted implements ConnectionSource {
        private final Server server;
        private final ServerDescription serverDescription;

        private ClusterBindingConnectionSource(final ServerSelector serverSelector) {
            ServerTuple serverTuple = cluster.selectServer(serverSelector);
            this.server = serverTuple.getServer();
            this.serverDescription = serverTuple.getServerDescription();
            ClusterBinding.this.retain();
        }

        @Override
        public ServerDescription getServerDescription() {
            return serverDescription;
        }

        @Override
        public SessionContext getSessionContext() {
            return new ReadConcernAwareNoOpSessionContext(readConcern);
        }

        @Override
        public ServerApi getServerApi() {
            return serverApi;
        }

        @Override
        public Connection getConnection() {
            return server.getConnection();
        }

        public ConnectionSource retain() {
            super.retain();
            ClusterBinding.this.retain();
            return this;
        }

        @Override
        public void release() {
            super.release();
            ClusterBinding.this.release();
        }
    }
}
