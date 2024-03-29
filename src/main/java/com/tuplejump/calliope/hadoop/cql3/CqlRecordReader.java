/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tuplejump.calliope.hadoop.cql3;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Maps;
import com.tuplejump.calliope.hadoop.ColumnFamilySplit;
import com.tuplejump.calliope.hadoop.ConfigHelper;
import com.tuplejump.calliope.hadoop.MultiRangeSplit;
import com.tuplejump.calliope.hadoop.TokenRangeHolder;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.utils.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * CqlRecordReader reads the rows return from the CQL query
 * It uses CQL auto-paging.
 * <p/>
 * Return a Long as a local CQL row key starts from 0;
 * <p/>
 * Row as C* java driver CQL result set row
 * 1) select clause must include partition key columns (to calculate the progress based on the actual CF row processed)
 * 2) where clause must include token(partition_key1, ...  , partition_keyn) > ? and
 * token(partition_key1, ... , partition_keyn) <= ?  (in the right order)
 */
public class CqlRecordReader extends RecordReader<Long, Row>
        implements org.apache.hadoop.mapred.RecordReader<Long, Row> {
    private static final Logger logger = LoggerFactory.getLogger(CqlRecordReader.class);
    private InputSplit split;
    private RowIterator rowIterator;

    private Pair<Long, Row> currentRow;
    private int totalRowCount; // total number of rows to fetch
    private String keyspace;
    private String cfName;
    private String cqlQuery;
    private Cluster cluster;
    private Session session;
    private IPartitioner partitioner;

    // partition keys -- key aliases
    private LinkedHashMap<String, Boolean> partitionBoundColumns = Maps.newLinkedHashMap();

    public CqlRecordReader() {
        super();
        logger.info("Creating CQL Record Reader");
    }

    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
        if (CqlConfigHelper.getMultiRangeInputSplit(context.getConfiguration())) {
            logger.info("Initializing Record reader with MultiRangeSplit");
            initializeWithMultiRangeSplit(split, context);
        } else {
            logger.info("Initializing Record reader with SingleRangeSplit");
            initializeWithColumnFamilySplit(split, context);
        }
    }

    private void initializeWithColumnFamilySplit(InputSplit split, TaskAttemptContext context) throws IOException {
        this.split = split;
        ColumnFamilySplit cfSplit = (ColumnFamilySplit) split;
        Configuration conf = context.getConfiguration();
        totalRowCount = (cfSplit.getLength() < Long.MAX_VALUE)
                ? (int) cfSplit.getLength()
                : ConfigHelper.getInputSplitSize(conf);
        cfName = quote(ConfigHelper.getInputColumnFamily(conf));
        keyspace = quote(ConfigHelper.getInputKeyspace(conf));
        cqlQuery = CqlConfigHelper.getInputCql(conf);
        partitioner = ConfigHelper.getInputPartitioner(context.getConfiguration());

        try {
            if (cluster != null)
                return;
            // create connection using thrift
            String[] locations = split.getLocations();

            Exception lastException = null;
            for (String location : locations) {
                try {
                    cluster = CqlConfigHelper.getInputCluster(location, conf);
                    break;
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Failed to create authenticated client to {}", location);
                }
            }
            if (cluster == null && lastException != null)
                throw lastException;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (cluster != null) {
            try {
                session = cluster.connect(keyspace);
            } catch (NoHostAvailableException nha) {
                Map<InetSocketAddress, Throwable> errors = nha.getErrors();
                logger.error(errors.toString());
                for (InetSocketAddress isa : errors.keySet()) {
                    logger.error("ERROR ON HOST [" + isa.getAddress() + "/" + isa.getPort() + "] ");
                    logger.error(errors.get(isa).getMessage());
                    logger.error("Connection Timeout:  " + cluster.getConfiguration().getSocketOptions().getConnectTimeoutMillis());
                    logger.error("Local connection limit:  " + cluster.getConfiguration().getPoolingOptions().getCoreConnectionsPerHost(HostDistance.LOCAL));
                    logger.error("Remote connection limit:  " + cluster.getConfiguration().getPoolingOptions().getCoreConnectionsPerHost(HostDistance.REMOTE));
                    //logger.error("Connection Timeout:  " + cluster.getConfiguration().getSocketOptions().);
                }
                throw nha;
            }
        }
        rowIterator = new SingleRangeRowIterator();
        logger.debug("created {}", rowIterator);
    }

    private void initializeWithMultiRangeSplit(InputSplit split, TaskAttemptContext context) throws IOException {
        this.split = split;
        MultiRangeSplit cfSplit = (MultiRangeSplit) split;
        Configuration conf = context.getConfiguration();
        totalRowCount = (cfSplit.getLength() < Long.MAX_VALUE)
                ? (int) cfSplit.getLength()
                : ConfigHelper.getInputSplitSize(conf);
        cfName = quote(ConfigHelper.getInputColumnFamily(conf));
        keyspace = quote(ConfigHelper.getInputKeyspace(conf));
        cqlQuery = CqlConfigHelper.getInputCql(conf);
        partitioner = ConfigHelper.getInputPartitioner(context.getConfiguration());

        try {
            if (cluster != null)
                return;
            // create connection using thrift
            String[] locations = split.getLocations();

            Exception lastException = null;
            for (String location : locations) {
                try {
                    cluster = CqlConfigHelper.getInputCluster(location, conf);
                    break;
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Failed to create authenticated client to {}", location);
                }
            }
            if (cluster == null && lastException != null)
                throw lastException;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (cluster != null) {
            try {
                session = cluster.connect(keyspace);
            } catch (NoHostAvailableException nha) {
                Map<InetSocketAddress, Throwable> errors = nha.getErrors();
                logger.error(errors.toString());
                for (InetSocketAddress isa : errors.keySet()) {
                    logger.error("ERROR ON HOST [" + isa.getAddress() + "/" + isa.getPort() + "] ");
                    logger.error(errors.get(isa).getMessage());
                    logger.error("Connection Timeout:  " + cluster.getConfiguration().getSocketOptions().getConnectTimeoutMillis());
                    logger.error("Local connection limit:  " + cluster.getConfiguration().getPoolingOptions().getCoreConnectionsPerHost(HostDistance.LOCAL));
                    logger.error("Remote connection limit:  " + cluster.getConfiguration().getPoolingOptions().getCoreConnectionsPerHost(HostDistance.REMOTE));
                    //logger.error("Connection Timeout:  " + cluster.getConfiguration().getSocketOptions().);
                }
                throw nha;
            }
        }
        rowIterator = new MultiRangeRowIterator();
        logger.debug("created {}", rowIterator);
    }


    public void close() {
        if (session != null)
            session.close();
        if (cluster != null)
            cluster.close();
    }

    public Long getCurrentKey() {
        return currentRow.left;
    }

    public Row getCurrentValue() {
        return currentRow.right;
    }

    public float getProgress() {
        if (!rowIterator.hasNext())
            return 1.0F;

        // the progress is likely to be reported slightly off the actual but close enough
        float progress = ((float) rowIterator.totalRead / totalRowCount);
        return progress > 1.0F ? 1.0F : progress;
    }

    public boolean nextKeyValue() throws IOException {
        if (!rowIterator.hasNext()) {
            logger.debug("Finished scanning {} rows (estimate was: {})", rowIterator.totalRead, totalRowCount);
            return false;
        }

        try {
            currentRow = rowIterator.next();
        } catch (Exception e) {
            // throw it as IOException, so client can catch it and handle it at client side
            IOException ioe = new IOException(e.getMessage());
            ioe.initCause(ioe.getCause());
            throw ioe;
        }
        return true;
    }

    // Because the old Hadoop API wants us to write to the key and value
    // and the new asks for them, we need to copy the output of the new API
    // to the old. Thus, expect a small performance hit.
    // And obviously this wouldn't work for wide rows. But since ColumnFamilyInputFormat
    // and ColumnFamilyRecordReader don't support them, it should be fine for now.
    public boolean next(Long key, Row value) throws IOException {
        if (nextKeyValue()) {
            ((WrappedRow) value).setRow(getCurrentValue());
            return true;
        }
        return false;
    }

    public long getPos() throws IOException {
        return (long) rowIterator.totalRead;
    }

    public Long createKey() {
        return new Long(0L);
    }

    public Row createValue() {
        return new WrappedRow();
    }


    private abstract class RowIterator extends AbstractIterator<Pair<Long, Row>> {
        protected int totalRead = 0; // total number of cf rows read
    }

    /**
     * CQL row iterator
     * Input cql query
     * 1) select clause must include key columns (if we use partition key based row count)
     * 2) where clause must include token(partition_key1 ... partition_keyn) > ? and
     * token(partition_key1 ... partition_keyn) <= ?
     */
    private class SingleRangeRowIterator extends RowIterator {
        private long keyId = 0L;
        protected Iterator<Row> rows;
        private Map<String, ByteBuffer> previousRowKey = new HashMap<String, ByteBuffer>(); // previous CF row key

        public SingleRangeRowIterator() {
            ColumnFamilySplit cfSplit = (ColumnFamilySplit) split;
            if (session == null)
                throw new RuntimeException("Can't create connection session");

            AbstractType type = partitioner.getTokenValidator();

            if (logger.isDebugEnabled()) {
                logger.debug("QUERY: " + cqlQuery);
                logger.debug("START: " + cfSplit.getStartToken());
                logger.debug("END: " + cfSplit.getEndToken());
            }

            ResultSet rs = session.execute(cqlQuery, type.compose(type.fromString(cfSplit.getStartToken())), type.compose(type.fromString(cfSplit.getEndToken())));
            for (ColumnMetadata meta : cluster.getMetadata().getKeyspace(keyspace).getTable(cfName).getPartitionKey())
                partitionBoundColumns.put(meta.getName(), Boolean.TRUE);
            rows = rs.iterator();
        }

        protected Pair<Long, Row> computeNext() {
            if (rows == null || !rows.hasNext())
                return endOfData();

            Row row = rows.next();
            Map<String, ByteBuffer> keyColumns = new HashMap<String, ByteBuffer>();
            for (String column : partitionBoundColumns.keySet())
                keyColumns.put(column, row.getBytesUnsafe(column));

            // increase total CF row read
            if (previousRowKey.isEmpty() && !keyColumns.isEmpty()) {
                previousRowKey = keyColumns;
                totalRead++;
            } else {
                for (String column : partitionBoundColumns.keySet()) {
                    if (BytesType.bytesCompare(keyColumns.get(column), previousRowKey.get(column)) != 0) {
                        previousRowKey = keyColumns;
                        totalRead++;
                        break;
                    }
                }
            }
            keyId++;
            return Pair.create(keyId, row);
        }
    }


    /**
     * CQL row iterator
     * Input cql query
     * 1) select clause must include key columns (if we use partition key based row count)
     * 2) where clause must include token(partition_key1 ... partition_keyn) > ? and
     * token(partition_key1 ... partition_keyn) <= ?
     */
    private class MultiRangeRowIterator extends RowIterator {
        private long keyId = 0L;
        protected Iterator<Row> currentRangeRows;
        private TokenRangeHolder[] tokenRanges;
        private int currentRange;
        private Map<String, ByteBuffer> previousRowKey = new HashMap<String, ByteBuffer>(); // previous CF row key
        AbstractType validatorType;

        public MultiRangeRowIterator() {
            MultiRangeSplit cfSplit = (MultiRangeSplit) split;
            if (session == null)
                throw new RuntimeException("Can't create connection session");

            validatorType = partitioner.getTokenValidator();

            if (logger.isDebugEnabled()) {
                logger.debug("QUERY: " + cqlQuery);
                logger.debug("Multi Range length is " + cfSplit.getLength());
            }

            logger.info("Created new MultiRangeRowIterator");
            tokenRanges = cfSplit.getTokenRanges();
            currentRange = 0;
        }

        private Iterator<Row> getNextRange() {
            //if (logger.isDebugEnabled())
            logger.debug(String.format("Processing new token range. %d more to go!", tokenRanges.length - currentRange));

            TokenRangeHolder range = tokenRanges[currentRange];
            ResultSet rs = session.execute(cqlQuery, validatorType.compose(validatorType.fromString(range.getStartToken())), validatorType.compose(validatorType.fromString(range.getEndToken())));
            for (ColumnMetadata meta : cluster.getMetadata().getKeyspace(keyspace).getTable(cfName).getPartitionKey())
                partitionBoundColumns.put(meta.getName(), Boolean.TRUE);

            currentRange++;

            return rs.iterator();
        }

        private Row getNextRow() {
            if (currentRangeRows == null || !currentRangeRows.hasNext()) {
                do {
                    currentRangeRows = getNextRange();
                } while (!currentRangeRows.hasNext() && tokenRanges.length > currentRange);
            }

            if (currentRangeRows == null) {
                return null;
            } else {
                return currentRangeRows.next();
            }
        }

        protected Pair<Long, Row> computeNext() {
            Row row = getNextRow();

            if (row == null) {
                logger.info("Done processing all ranges!");
                return endOfData();
            }

            if (logger.isDebugEnabled()) {
                logger.info(String.format("Got new row. Row # %d of total # %d", totalRead, totalRowCount));
            }

            Map<String, ByteBuffer> keyColumns = new HashMap<String, ByteBuffer>();
            for (String column : partitionBoundColumns.keySet())
                keyColumns.put(column, row.getBytesUnsafe(column));

            // increase total CF row read
            if (previousRowKey.isEmpty() && !keyColumns.isEmpty()) {
                previousRowKey = keyColumns;
                totalRead++;
            } else {
                for (String column : partitionBoundColumns.keySet()) {
                    if (BytesType.bytesCompare(keyColumns.get(column), previousRowKey.get(column)) != 0) {
                        previousRowKey = keyColumns;
                        totalRead++;
                        break;
                    }
                }
            }
            keyId++;
            return Pair.create(keyId, row);
        }
    }


    private static class WrappedRow implements Row {
        private Row row;

        public void setRow(Row row) {
            this.row = row;
        }

        @Override
        public ColumnDefinitions getColumnDefinitions() {
            return row.getColumnDefinitions();
        }

        @Override
        public boolean isNull(int i) {
            return row.isNull(i);
        }

        @Override
        public boolean isNull(String name) {
            return row.isNull(name);
        }

        @Override
        public boolean getBool(int i) {
            return row.getBool(i);
        }

        @Override
        public boolean getBool(String name) {
            return row.getBool(name);
        }

        @Override
        public int getInt(int i) {
            return row.getInt(i);
        }

        @Override
        public int getInt(String name) {
            return row.getInt(name);
        }

        @Override
        public long getLong(int i) {
            return row.getLong(i);
        }

        @Override
        public long getLong(String name) {
            return row.getLong(name);
        }

        @Override
        public Date getDate(int i) {
            return row.getDate(i);
        }

        @Override
        public Date getDate(String name) {
            return row.getDate(name);
        }

        @Override
        public float getFloat(int i) {
            return row.getFloat(i);
        }

        @Override
        public float getFloat(String name) {
            return row.getFloat(name);
        }

        @Override
        public double getDouble(int i) {
            return row.getDouble(i);
        }

        @Override
        public double getDouble(String name) {
            return row.getDouble(name);
        }

        @Override
        public ByteBuffer getBytesUnsafe(int i) {
            return row.getBytesUnsafe(i);
        }

        @Override
        public ByteBuffer getBytesUnsafe(String name) {
            return row.getBytesUnsafe(name);
        }

        @Override
        public ByteBuffer getBytes(int i) {
            return row.getBytes(i);
        }

        @Override
        public ByteBuffer getBytes(String name) {
            return row.getBytes(name);
        }

        @Override
        public String getString(int i) {
            return row.getString(i);
        }

        @Override
        public String getString(String name) {
            return row.getString(name);
        }

        @Override
        public BigInteger getVarint(int i) {
            return row.getVarint(i);
        }

        @Override
        public BigInteger getVarint(String name) {
            return row.getVarint(name);
        }

        @Override
        public BigDecimal getDecimal(int i) {
            return row.getDecimal(i);
        }

        @Override
        public BigDecimal getDecimal(String name) {
            return row.getDecimal(name);
        }

        @Override
        public UUID getUUID(int i) {
            return row.getUUID(i);
        }

        @Override
        public UUID getUUID(String name) {
            return row.getUUID(name);
        }

        @Override
        public InetAddress getInet(int i) {
            return row.getInet(i);
        }

        @Override
        public InetAddress getInet(String name) {
            return row.getInet(name);
        }

        @Override
        public <T> List<T> getList(int i, Class<T> elementsClass) {
            return row.getList(i, elementsClass);
        }

        @Override
        public <T> List<T> getList(String name, Class<T> elementsClass) {
            return row.getList(name, elementsClass);
        }

        @Override
        public <T> Set<T> getSet(int i, Class<T> elementsClass) {
            return row.getSet(i, elementsClass);
        }

        @Override
        public <T> Set<T> getSet(String name, Class<T> elementsClass) {
            return row.getSet(name, elementsClass);
        }

        @Override
        public <K, V> Map<K, V> getMap(int i, Class<K> keysClass, Class<V> valuesClass) {
            return row.getMap(i, keysClass, valuesClass);
        }

        @Override
        public <K, V> Map<K, V> getMap(String name, Class<K> keysClass, Class<V> valuesClass) {
            return row.getMap(name, keysClass, valuesClass);
        }
    }

    private String quote(String identifier) {
        return "\"" + identifier.replaceAll("\"", "\"\"") + "\"";
    }
}
