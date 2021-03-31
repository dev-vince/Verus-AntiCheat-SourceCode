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

import org.bson.BsonDocument;
import org.bson.BsonTimestamp;

public class ClusterClock {
    private static final String CLUSTER_TIME_KEY = "clusterTime";
    private BsonDocument clusterTime;

    public synchronized BsonDocument getCurrent() {
        return clusterTime;
    }

    public synchronized BsonTimestamp getClusterTime() {
        return clusterTime != null ? clusterTime.getTimestamp(CLUSTER_TIME_KEY) : null;
    }

    public synchronized void advance(final BsonDocument other) {
        this.clusterTime = greaterOf(other);
    }

    public synchronized BsonDocument greaterOf(final BsonDocument other) {
        if (other == null) {
            return clusterTime;
        } else if (clusterTime == null) {
            return other;
        } else {
            return other.getTimestamp(CLUSTER_TIME_KEY).compareTo(clusterTime.getTimestamp(CLUSTER_TIME_KEY)) > 0 ? other : clusterTime;
        }
    }
}
