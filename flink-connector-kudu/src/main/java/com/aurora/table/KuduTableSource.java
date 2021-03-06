package com.aurora.table;


import com.aurora.connector.KuduFilterInfo;
import com.aurora.connector.KuduTableInfo;
import com.aurora.batch.KuduRowInputFormat;
import com.aurora.connector.reader.KuduReaderConfig;
import com.aurora.table.lookup.KuduLookupFunction;
import com.aurora.table.lookup.KuduLookupOptions;
import com.aurora.table.utils.KuduTableUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.functions.AsyncTableFunction;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.sources.*;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;

/**
 * @author lj.michale
 * @description
 * @date 2021-07-07
 */
public class KuduTableSource implements StreamTableSource<Row>,
        LimitableTableSource<Row>, ProjectableTableSource<Row>, FilterableTableSource<Row>, LookupableTableSource<Row> {

    private static final Logger LOG = LoggerFactory.getLogger(KuduTableSource.class);

    private final KuduReaderConfig.Builder configBuilder;
    private final KuduTableInfo tableInfo;
    private final TableSchema flinkSchema;
    private final String[] projectedFields;
    // predicate expression to apply
    @Nullable
    private final List<KuduFilterInfo> predicates;
    private boolean isFilterPushedDown;
    private final KuduLookupOptions kuduLookupOptions;

    private final KuduRowInputFormat kuduRowInputFormat;

    public KuduTableSource(KuduReaderConfig.Builder configBuilder, KuduTableInfo tableInfo,
                           TableSchema flinkSchema, List<KuduFilterInfo> predicates, String[] projectedFields, KuduLookupOptions kuduLookupOptions) {
        this.configBuilder = configBuilder;
        this.tableInfo = tableInfo;
        this.flinkSchema = flinkSchema;
        this.predicates = predicates;
        this.projectedFields = projectedFields;
        if (predicates != null && predicates.size() != 0) {
            this.isFilterPushedDown = true;
        }
        this.kuduRowInputFormat = new KuduRowInputFormat(configBuilder.build(), tableInfo,
                predicates == null ? Collections.emptyList() : predicates,
                projectedFields == null ? null : Lists.newArrayList(projectedFields));
        this.kuduLookupOptions = kuduLookupOptions;
    }

    @Override
    public boolean isBounded() {
        return true;
    }

    @Override
    public DataStream<Row> getDataStream(StreamExecutionEnvironment env) {
        KuduRowInputFormat inputFormat = new KuduRowInputFormat(configBuilder.build(), tableInfo,
                predicates == null ? Collections.emptyList() : predicates,
                projectedFields == null ? null : Lists.newArrayList(projectedFields));
        return env.createInput(inputFormat,
                (TypeInformation<Row>) TypeConversions.fromDataTypeToLegacyInfo(getProducedDataType()))
                .name(explainSource());
    }

    @Override
    public TableSchema getTableSchema() {
        return flinkSchema;
    }

    @Override
    public boolean isFilterPushedDown() {
        return this.isFilterPushedDown;
    }

    @Override
    public DataType getProducedDataType() {
        if (projectedFields == null) {
            return flinkSchema.toRowDataType();
        } else {
            DataTypes.Field[] fields = new DataTypes.Field[projectedFields.length];
            for (int i = 0; i < fields.length; i++) {
                String fieldName = projectedFields[i];
                fields[i] = DataTypes.FIELD(
                        fieldName,
                        flinkSchema
                                .getTableColumn(fieldName)
                                .get()
                                .getType()
                );
            }
            return DataTypes.ROW(fields);
        }
    }

    @Override
    public boolean isLimitPushedDown() {
        return true;
    }

    @Override
    public TableSource<Row> applyLimit(long l) {
        return new KuduTableSource(configBuilder.setRowLimit((int) l), tableInfo, flinkSchema,
                predicates, projectedFields, kuduLookupOptions);
    }

    @Override
    public TableSource<Row> projectFields(int[] ints) {
        String[] fieldNames = new String[ints.length];
        RowType producedDataType = (RowType) getProducedDataType().getLogicalType();
        List<String> prevFieldNames = producedDataType.getFieldNames();
        for (int i = 0; i < ints.length; i++) {
            fieldNames[i] = prevFieldNames.get(ints[i]);
        }
        return new KuduTableSource(configBuilder, tableInfo, flinkSchema, predicates, fieldNames, kuduLookupOptions);
    }

    @Override
    public TableSource<Row> applyPredicate(List<Expression> predicates) {
        List<KuduFilterInfo> kuduPredicates = new ArrayList<>();
        ListIterator<Expression> predicatesIter = predicates.listIterator();
        while (predicatesIter.hasNext()) {
            Expression predicate = predicatesIter.next();
            Optional<KuduFilterInfo> kuduPred = KuduTableUtils.toKuduFilterInfo(predicate);
            if (kuduPred != null && kuduPred.isPresent()) {
                LOG.debug("Predicate [{}] converted into KuduFilterInfo and pushed into " +
                        "KuduTable [{}].", predicate, tableInfo.getName());
                kuduPredicates.add(kuduPred.get());
                predicatesIter.remove();
            } else {
                LOG.debug("Predicate [{}] could not be pushed into KuduFilterInfo for KuduTable [{}].",
                        predicate, tableInfo.getName());
            }
        }
        return new KuduTableSource(configBuilder, tableInfo, flinkSchema, kuduPredicates, projectedFields, kuduLookupOptions);
    }

    @Override
    public String explainSource() {
        return "KuduTableSource[schema=" + Arrays.toString(getTableSchema().getFieldNames()) +
                ", filter=" + predicateString() +
                (projectedFields != null ? ", projectFields=" + Arrays.toString(projectedFields) + "]" : "]");
    }

    private String predicateString() {
        if (predicates == null || predicates.size() == 0) {
            return "No predicates push down";
        } else {
            return "AND(" + predicates + ")";
        }
    }

    @Override
    public TableFunction<Row> getLookupFunction(String[] lookupKeys) {
        Preconditions.checkArgument(
                ArrayUtils.isNotEmpty(lookupKeys),
                "Kudu table lookup key is not empty.");
        return KuduLookupFunction.Builder.options()
                .keyNames(lookupKeys)
                .kuduReaderConfig(configBuilder.build())
                .projectedFields(projectedFields)
                .tableInfo(tableInfo)
                .kuduLookupOptions(kuduLookupOptions)
                .build();
    }

    @Override
    public AsyncTableFunction<Row> getAsyncLookupFunction(String[] lookupKeys) {
        throw new UnsupportedOperationException("Kudu table doesn't support async lookup currently.");
    }

    @Override
    public boolean isAsyncEnabled() {
        return false;
    }
}