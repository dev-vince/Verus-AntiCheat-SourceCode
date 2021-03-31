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

package me.levansj01.verus.util.mongodb;

import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.codecs.Decoder;
import org.bson.codecs.DecoderContext;

import me.levansj01.verus.util.mongodb.DBObject;
import me.levansj01.verus.util.mongodb.MongoBulkWriteException;
import me.levansj01.verus.util.mongodb.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

final class BulkWriteHelper {

    static BulkWriteResult translateBulkWriteResult(final me.levansj01.verus.util.mongodb.bulk.BulkWriteResult bulkWriteResult,
                                                    final Decoder<DBObject> decoder) {
        if (bulkWriteResult.wasAcknowledged()) {
            return new AcknowledgedBulkWriteResult(bulkWriteResult.getInsertedCount(), bulkWriteResult.getMatchedCount(),
                                                   bulkWriteResult.getDeletedCount(), bulkWriteResult.getModifiedCount(),
                                                   translateBulkWriteUpserts(bulkWriteResult.getUpserts(), decoder));
        } else {
            return new UnacknowledgedBulkWriteResult();
        }
    }

    static List<BulkWriteUpsert> translateBulkWriteUpserts(final List<me.levansj01.verus.util.mongodb.bulk.BulkWriteUpsert> upserts,
                                                           final Decoder<DBObject> decoder) {
        List<BulkWriteUpsert> retVal = new ArrayList<BulkWriteUpsert>(upserts.size());
        for (me.levansj01.verus.util.mongodb.bulk.BulkWriteUpsert cur : upserts) {
            retVal.add(new me.levansj01.verus.util.mongodb.BulkWriteUpsert(cur.getIndex(), getUpsertedId(cur, decoder)));
        }
        return retVal;
    }

    private static Object getUpsertedId(final me.levansj01.verus.util.mongodb.bulk.BulkWriteUpsert cur, final Decoder<DBObject> decoder) {
        return decoder.decode(new BsonDocumentReader(new BsonDocument("_id", cur.getId())), DecoderContext.builder().build()).get("_id");
    }

    static BulkWriteException translateBulkWriteException(final MongoBulkWriteException e, final Decoder<DBObject> decoder) {
        return new BulkWriteException(translateBulkWriteResult(e.getWriteResult(), decoder), translateWriteErrors(e.getWriteErrors()),
                                           translateWriteConcernError(e.getWriteConcernError()), e.getServerAddress());
    }

    @Nullable
    static WriteConcernError translateWriteConcernError(@Nullable final me.levansj01.verus.util.mongodb.bulk.WriteConcernError writeConcernError) {
        return writeConcernError == null ? null : new WriteConcernError(writeConcernError.getCode(), writeConcernError.getMessage(),
                                                                        DBObjects.toDBObject(writeConcernError.getDetails()));
    }

    static List<BulkWriteError> translateWriteErrors(final List<me.levansj01.verus.util.mongodb.bulk.BulkWriteError> errors) {
        List<BulkWriteError> retVal = new ArrayList<BulkWriteError>(errors.size());
        for (me.levansj01.verus.util.mongodb.bulk.BulkWriteError cur : errors) {
            retVal.add(new BulkWriteError(cur.getCode(), cur.getMessage(), DBObjects.toDBObject(cur.getDetails()), cur.getIndex()));
        }
        return retVal;
    }

    private BulkWriteHelper() {
    }
}
