package com.github.shyiko.mysql.binlog.event.deserialization;

import com.binlog.analyzer.service.BinlogDeserializerFactory;
import com.github.luben.zstd.ZstdInputStream;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * The stock deserializer builds a fresh EventDeserializer for the inner events of a compressed
 * transaction, which would fall back to the library's incorrect TIME_V2 decoding. This subclass
 * builds that inner deserializer through {@link BinlogDeserializerFactory} so the patched row
 * deserializers are used there as well. It lives in the connector package to reach the
 * package-private InnerEventIterator constructor.
 */
public class TimeSafeTransactionPayloadEventDataDeserializer extends TransactionPayloadEventDataDeserializer {

    @Override
    public InnerEventIterator iterator(TransactionPayloadEventData eventData) throws IOException {
        EventDeserializer inner = BinlogDeserializerFactory.create();
        InputStream payload = eventData.getPayloadInputStream();
        if (payload == null) {
            byte[] bytes = eventData.getPayload();
            if (bytes == null) {
                throw new IOException("Transaction payload is not available");
            }
            payload = new java.io.ByteArrayInputStream(bytes);
        }
        InputStream decompressed;
        switch (eventData.getCompressionType()) {
            case COMPRESSION_TYPE_ZSTD:
                decompressed = new ZstdInputStream(payload);
                break;
            case COMPRESSION_TYPE_NONE:
                decompressed = payload;
                break;
            default:
                throw new IOException("Unsupported binlog_transaction_compression type: "
                        + eventData.getCompressionType() + " (only ZSTD and NONE are supported)");
        }
        return new InnerEventIterator(inner, new ByteArrayInputStream(decompressed),
                eventData.getUncompressedSize());
    }
}
