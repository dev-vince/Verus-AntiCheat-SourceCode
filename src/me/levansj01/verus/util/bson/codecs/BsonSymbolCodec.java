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

package me.levansj01.verus.util.bson.codecs;

import me.levansj01.verus.util.bson.BsonReader;
import me.levansj01.verus.util.bson.BsonSymbol;
import me.levansj01.verus.util.bson.BsonWriter;

/**
 * A codec for BSON symbol type.
 *
 * @since 3.0
 */
public class BsonSymbolCodec implements Codec<BsonSymbol> {
    @Override
    public BsonSymbol decode(final BsonReader reader, final DecoderContext decoderContext) {
        return new BsonSymbol(reader.readSymbol());
    }

    @Override
    public void encode(final BsonWriter writer, final BsonSymbol value, final EncoderContext encoderContext) {
        writer.writeSymbol(value.getSymbol());
    }

    @Override
    public Class<BsonSymbol> getEncoderClass() {
        return BsonSymbol.class;
    }
}
