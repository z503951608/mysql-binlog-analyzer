package com.binlog.analyzer.service;

import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.DeleteRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.UpdateRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.WriteRowsEventDataDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

/**
 * mysql-binlog-connector-java 0.31.0 decodes TIME_V2 without honoring the sign bit, so negative
 * values such as -01:02:03 come back as bogus positive times. Row deserializers below share the
 * table map with the owning EventDeserializer and replace only the TIME_V2 decoding.
 */
final class TimeV2RowDeserializers {

    private TimeV2RowDeserializers() {
    }

    static WriteRowsEventDataDeserializer write(Map<Long, TableMapEventData> tableMap) {
        return new FixedWriteRows(tableMap);
    }

    static WriteRowsEventDataDeserializer extWrite(Map<Long, TableMapEventData> tableMap) {
        return new FixedWriteRows(tableMap).setMayContainExtraInformation(true);
    }

    static UpdateRowsEventDataDeserializer update(Map<Long, TableMapEventData> tableMap) {
        return new FixedUpdateRows(tableMap);
    }

    static UpdateRowsEventDataDeserializer extUpdate(Map<Long, TableMapEventData> tableMap) {
        return new FixedUpdateRows(tableMap).setMayContainExtraInformation(true);
    }

    static DeleteRowsEventDataDeserializer delete(Map<Long, TableMapEventData> tableMap) {
        return new FixedDeleteRows(tableMap);
    }

    static DeleteRowsEventDataDeserializer extDelete(Map<Long, TableMapEventData> tableMap) {
        return new FixedDeleteRows(tableMap).setMayContainExtraInformation(true);
    }

    private static Serializable decodeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
        int raw = 0;
        for (int i = 0; i < 3; i++) {
            raw = (raw << 8) | (inputStream.read() & 0xFF);
        }
        int integerPart = raw - 0x800000;
        int fractionLength = (meta + 1) / 2;
        long fraction = 0;
        for (int i = 0; i < fractionLength; i++) {
            fraction = (fraction << 8) | (inputStream.read() & 0xFF);
        }
        if (fractionLength > 0) {
            // Negative values store the fractional part reversed (two's complement) for binary sorting.
            if (integerPart < 0 && fraction != 0) {
                integerPart++;
                fraction -= 1L << (fractionLength * 8);
            }
            if (fractionLength == 1) {
                fraction *= 10_000L;
            } else if (fractionLength == 2) {
                fraction *= 100L;
            }
        }

        long packed = ((long) integerPart << 24) + fraction;
        boolean negative = packed < 0;
        long magnitude = Math.abs(packed);
        long hms = magnitude >>> 24;
        long hour = (hms >>> 12) & 0x3FF;
        long minute = (hms >>> 6) & 0x3F;
        long second = hms & 0x3F;
        long micros = (hour * 3600L + minute * 60L + second) * 1_000_000L + (magnitude & 0xFFFFFF);
        return negative ? -micros : micros;
    }

    private static final class FixedWriteRows extends WriteRowsEventDataDeserializer {

        private FixedWriteRows(Map<Long, TableMapEventData> tableMap) {
            super(tableMap);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return decodeTimeV2(meta, inputStream);
        }
    }

    private static final class FixedUpdateRows extends UpdateRowsEventDataDeserializer {

        private FixedUpdateRows(Map<Long, TableMapEventData> tableMap) {
            super(tableMap);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return decodeTimeV2(meta, inputStream);
        }
    }

    private static final class FixedDeleteRows extends DeleteRowsEventDataDeserializer {

        private FixedDeleteRows(Map<Long, TableMapEventData> tableMap) {
            super(tableMap);
        }

        @Override
        protected Serializable deserializeTimeV2(int meta, ByteArrayInputStream inputStream) throws IOException {
            return decodeTimeV2(meta, inputStream);
        }
    }
}
