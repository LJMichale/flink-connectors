package com.aurora.connector.reader;

import com.aurora.connector.KuduFilterInfo;
import com.aurora.connector.KuduTableInfo;
import org.apache.commons.collections.CollectionUtils;
import org.apache.flink.annotation.Internal;
import org.apache.kudu.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author lj.michale
 * @description  kudu 读取器
 * @date 2021-07-07
 */
@Internal
public class KuduReader implements AutoCloseable {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * kudu表操作客户端
     */
    private final KuduTableInfo tableInfo;
    /**
     * kudu读取配置
     */
    private final KuduReaderConfig readerConfig;
    /**
     * kudu表过滤信息
     */
    private List<KuduFilterInfo> tableFilters;
    private List<String> tableProjections;

    private transient KuduClient client;
    private transient KuduSession session;
    private transient KuduTable table;

    public KuduReader(KuduTableInfo tableInfo, KuduReaderConfig readerConfig) throws IOException {
        this(tableInfo, readerConfig, new ArrayList<>(), null);
    }

    public KuduReader(KuduTableInfo tableInfo, KuduReaderConfig readerConfig, List<KuduFilterInfo> tableFilters) throws IOException {
        this(tableInfo, readerConfig, tableFilters, null);
    }

    public void setTableFilters(List<KuduFilterInfo> tableFilters) {
        this.tableFilters = tableFilters;
    }

    public void setTableProjections(List<String> tableProjections) {
        this.tableProjections = tableProjections;
    }

    public KuduReader(KuduTableInfo tableInfo, KuduReaderConfig readerConfig, List<KuduFilterInfo> tableFilters, List<String> tableProjections) throws IOException {
        this.tableInfo = tableInfo;
        this.readerConfig = readerConfig;
        this.tableFilters = tableFilters;
        this.tableProjections = tableProjections;

        this.client = obtainClient();
        this.session = obtainSession();
        this.table = obtainTable();
    }

    private KuduClient obtainClient() {
        return new KuduClient.KuduClientBuilder(readerConfig.getMasters()).build();
    }

    private KuduSession obtainSession() {
        return client.newSession();
    }

    private KuduTable obtainTable() throws IOException {
        String tableName = tableInfo.getName();
        if (client.tableExists(tableName)) {
            return client.openTable(tableName);
        }
        if (tableInfo.getCreateTableIfNotExists()) {
            return client.createTable(tableName, tableInfo.getSchema(), tableInfo.getCreateTableOptions());
        }
        throw new RuntimeException("Table " + tableName + " does not exist.");
    }

    public KuduReaderIterator scanner(byte[] token) throws IOException {
        return new KuduReaderIterator(KuduScanToken.deserializeIntoScanner(token, client));
    }

    public List<KuduScanToken> scanTokens(List<KuduFilterInfo> tableFilters, List<String> tableProjections, Integer rowLimit) {
        KuduScanToken.KuduScanTokenBuilder tokenBuilder = client.newScanTokenBuilder(table);

        if (tableProjections != null) {
            tokenBuilder.setProjectedColumnNames(tableProjections);
        }

        if (CollectionUtils.isNotEmpty(tableFilters)) {
            tableFilters.stream()
                    .map(filter -> filter.toPredicate(table.getSchema()))
                    .forEach(tokenBuilder::addPredicate);
        }

        if (rowLimit != null && rowLimit > 0) {
            tokenBuilder.limit(rowLimit);
        }

        return tokenBuilder.build();
    }

    public KuduInputSplit[] createInputSplits(int minNumSplits) throws IOException {

        List<KuduScanToken> tokens = scanTokens(tableFilters, tableProjections, readerConfig.getRowLimit());

        KuduInputSplit[] splits = new KuduInputSplit[tokens.size()];

        for (int i = 0; i < tokens.size(); i++) {
            KuduScanToken token = tokens.get(i);

            List<String> locations = new ArrayList<>(token.getTablet().getReplicas().size());

            for (LocatedTablet.Replica replica : token.getTablet().getReplicas()) {
                locations.add(getLocation(replica.getRpcHost(), replica.getRpcPort()));
            }

            KuduInputSplit split = new KuduInputSplit(
                    token.serialize(),
                    i,
                    locations.toArray(new String[locations.size()])
            );
            splits[i] = split;
        }

        if (splits.length < minNumSplits) {
            logger.warn(" The minimum desired number of splits with your configured parallelism level " +
                            "is {}. Current kudu splits = {}. {} instances will remain idle.",
                    minNumSplits,
                    splits.length,
                    (minNumSplits - splits.length)
            );
        }

        return splits;
    }

    /**
     * Returns a endpoint url in the following format: <host>:<ip>
     *
     * @param host Hostname
     * @param port Port
     * @return Formatted URL
     */
    private String getLocation(String host, Integer port) {
        StringBuilder builder = new StringBuilder();
        builder.append(host).append(":").append(port);
        return builder.toString();
    }

    @Override
    public void close() throws IOException {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception e) {
            logger.error("Error while closing session.", e);
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            logger.error("Error while closing client.", e);
        }
    }
}