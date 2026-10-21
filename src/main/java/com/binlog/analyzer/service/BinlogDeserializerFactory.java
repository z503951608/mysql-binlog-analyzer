package com.binlog.analyzer.service;

import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventHeaderV4Deserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.NullEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.TimeSafeTransactionPayloadEventDataDeserializer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class BinlogDeserializerFactory {

    private BinlogDeserializerFactory() {
    }

    public static EventDeserializer create() {
        Map<Long, TableMapEventData> tableMap = new HashMap<>();
        EventDeserializer deserializer = new EventDeserializer(
                new EventHeaderV4Deserializer(), new NullEventDataDeserializer(),
                defaultDeserializers(), tableMap);
        deserializer.setCompatibilityMode(
                EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY,
                EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG_MICRO);
        deserializer.setEventDataDeserializer(EventType.WRITE_ROWS, TimeV2RowDeserializers.write(tableMap));
        deserializer.setEventDataDeserializer(EventType.EXT_WRITE_ROWS, TimeV2RowDeserializers.extWrite(tableMap));
        deserializer.setEventDataDeserializer(EventType.UPDATE_ROWS, TimeV2RowDeserializers.update(tableMap));
        deserializer.setEventDataDeserializer(EventType.EXT_UPDATE_ROWS, TimeV2RowDeserializers.extUpdate(tableMap));
        deserializer.setEventDataDeserializer(EventType.DELETE_ROWS, TimeV2RowDeserializers.delete(tableMap));
        deserializer.setEventDataDeserializer(EventType.EXT_DELETE_ROWS, TimeV2RowDeserializers.extDelete(tableMap));
        deserializer.setEventDataDeserializer(EventType.TRANSACTION_PAYLOAD,
                new TimeSafeTransactionPayloadEventDataDeserializer());
        deserializer.setTransparentlyDecompressTransactions(true);
        return deserializer;
    }

    private static Map<EventType, EventDataDeserializer> defaultDeserializers() {
        EventDeserializer template = new EventDeserializer();
        Map<EventType, EventDataDeserializer> deserializers = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) {
            deserializers.put(type, template.getEventDataDeserializer(type));
        }
        return deserializers;
    }
}
