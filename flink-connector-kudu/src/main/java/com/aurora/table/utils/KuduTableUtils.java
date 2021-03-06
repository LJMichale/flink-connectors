package com.aurora.table.utils;

import com.aurora.connector.KuduFilterInfo;
import com.aurora.connector.KuduTableInfo;
import com.aurora.connector.ColumnSchemasFactory;
import com.aurora.connector.CreateTableOptionsFactory;
import com.aurora.table.KuduTableFactory;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.table.utils.TableSchemaUtils;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.ColumnTypeAttributes;
import org.apache.kudu.Schema;
import org.apache.kudu.client.CreateTableOptions;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.RangePartitionBound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author lj.michale
 * @description
 * @date 2021-07-07
 */
public class KuduTableUtils {

    private static final Logger LOG = LoggerFactory.getLogger(KuduTableUtils.class);

    public static KuduTableInfo createTableInfo(String tableName, TableSchema schema, Map<String, String> props) {
        // kudu主键或者table主键存在即新创建tableInfo
        boolean createIfMissing = props.containsKey(KuduTableFactory.KUDU_PRIMARY_KEY_COLS) || Objects.nonNull(schema.getPrimaryKey());
        boolean isHashPartition = props.containsKey(KuduTableFactory.KUDU_HASH_COLS);
        boolean isRangePartition = props.containsKey(KuduTableFactory.KUDU_RANGE_PARTITION_RULE);
        KuduTableInfo tableInfo = KuduTableInfo.forTable(tableName);

        if (createIfMissing) {

            List<Tuple2<String, DataType>> columns = getSchemaWithSqlTimestamp(schema)
                    .getTableColumns()
                    .stream()
                    .map(tc -> Tuple2.of(tc.getName(), tc.getType()))
                    .collect(Collectors.toList());

            List<String> keyColumns = getPrimaryKeyColumns(props, schema);
            ColumnSchemasFactory schemasFactory = () -> toKuduConnectorColumns(columns, keyColumns);
            int replicas = Optional.ofNullable(props.get(KuduTableFactory.KUDU_REPLICAS)).map(Integer::parseInt).orElse(1);
            // if hash partitions nums not exists,default 1;
            int hashPartitionNums = Optional.ofNullable(props.get(KuduTableFactory.KUDU_HASH_PARTITION_NUMS)).map(Integer::parseInt).orElse(3);
            CreateTableOptionsFactory optionsFactory = () -> {
                CreateTableOptions createTableOptions = new CreateTableOptions()
                        .setNumReplicas(replicas);
                if (isHashPartition) {
                    createTableOptions
                            .addHashPartitions(getHashColumns(props), hashPartitionNums);
                }
                // build rangePartition
                if (isRangePartition) {
                    buildRangePartition(createTableOptions, props, keyColumns, schemasFactory);
                }
                // 如果没有显式指定hash和range分区，则设置主键为range分区
                if (!isRangePartition && !isHashPartition) {
                    createTableOptions.setRangePartitionColumns(keyColumns);
                }
                return createTableOptions;
            };
            tableInfo.createTableIfNotExists(schemasFactory, optionsFactory);
        } else {
            LOG.debug("Property {} is missing, assuming the table is already created.", KuduTableFactory.KUDU_HASH_COLS);
        }

        return tableInfo;
    }

    public static List<ColumnSchema> toKuduConnectorColumns(List<Tuple2<String, DataType>> columns, Collection<String> keyColumns) {
        return columns.stream()
                .map(t -> {
                            ColumnSchema.ColumnSchemaBuilder builder = new ColumnSchema
                                    .ColumnSchemaBuilder(t.f0, KuduTypeUtils.toKuduType(t.f1))
                                    .key(keyColumns.contains(t.f0))
                                    .nullable(!keyColumns.contains(t.f0) && t.f1.getLogicalType().isNullable());
                            if (t.f1.getLogicalType() instanceof DecimalType) {
                                DecimalType decimalType = ((DecimalType) t.f1.getLogicalType());
                                builder.typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                                        .precision(decimalType.getPrecision())
                                        .scale(decimalType.getScale())
                                        .build());
                            }
                            return builder.build();
                        }
                ).collect(Collectors.toList());
    }

    public static TableSchema kuduToFlinkSchema(Schema schema) {
        TableSchema.Builder builder = TableSchema.builder();

        for (ColumnSchema column : schema.getColumns()) {
            DataType flinkType = KuduTypeUtils.toFlinkType(column.getType(), column.getTypeAttributes()).nullable();
            builder.field(column.getName(), flinkType);
        }

        return builder.build();
    }

    public static List<String> getPrimaryKeyColumns(Map<String, String> tableProperties, TableSchema tableSchema) {
        return tableProperties.containsKey(KuduTableFactory.KUDU_PRIMARY_KEY_COLS) ? Arrays.asList(tableProperties.get(KuduTableFactory.KUDU_PRIMARY_KEY_COLS).split(",")) : tableSchema.getPrimaryKey().get().getColumns();
    }

    /**
     * 构造range分区
     * 格式为:id#100,200#true,true:id#200,300#false,false
     *
     * @param createTableOptions
     * @param tableProperties
     */
    public static void buildRangePartition(CreateTableOptions createTableOptions, Map<String, String> tableProperties, List<String> primaryKeys, ColumnSchemasFactory schemasFactory) {
        String rangePartitionRule = tableProperties.get(KuduTableFactory.KUDU_RANGE_PARTITION_RULE);
        String[] ruleArr = rangePartitionRule.split(":");
        Set<String> rangeKeys = new HashSet<>();
        for (String rule : ruleArr) {
            String[] rangeRule = rule.split("#");
            if (ArrayUtils.isEmpty(rangeRule) || rangeRule.length != 2) {
                throw new IllegalArgumentException("range参数异常,正确格式为: rangeKey#leftValue,rightValue#leftBound,rightBound:rangeKey#leftValue,rightValue#leftBound,rightBound ");
            }
            String rangeKey = rangeRule[0];
            if (!primaryKeys.contains(rangeKey)) {
                throw new IllegalArgumentException("rangeKey必须从primary key中选择");
            }
            rangeKeys.add(rangeKey);
            String[] rangeValues = rangeRule[1].split(",");
            if (ArrayUtils.isEmpty(rangeValues)) {
                throw new IllegalArgumentException("rangeValues不能为空");
            }
            PartialRow lowerRow = new PartialRow(new Schema(schemasFactory.getColumnSchemas()));
            PartialRow upperRow = new PartialRow(new Schema(schemasFactory.getColumnSchemas()));
            // 封装分区范围
            if (rangeValues.length == 2) {
                String leftVal = rangeValues[0];
                String rightVal = rangeValues[1];
                if (StringUtils.isNotEmpty(leftVal) && StringUtils.isNotEmpty(rightVal)) {
                    if (leftVal.compareTo(rightVal) > 0) {
                        throw new IllegalArgumentException("leftValue不能大于rightValue");
                    }
                }
                if (StringUtils.isNotEmpty(leftVal)) {
                    buildRangeRow(schemasFactory.getColumnSchemas(), rangeKey, lowerRow, leftVal);
                }
                if (StringUtils.isNotEmpty(rightVal)) {
                    buildRangeRow(schemasFactory.getColumnSchemas(), rangeKey, upperRow, rightVal);
                }
            } else if (rangeValues.length == 1) {
                String leftVal = rangeValues[0];
                if (StringUtils.isNotEmpty(leftVal)) {
                    buildRangeRow(schemasFactory.getColumnSchemas(), rangeKey, lowerRow, leftVal);
                }
            }
            createTableOptions.addRangePartition(lowerRow, upperRow, RangePartitionBound.INCLUSIVE_BOUND, RangePartitionBound.EXCLUSIVE_BOUND);
        }
        List<String> ranges = new ArrayList<>(rangeKeys);
        createTableOptions.setRangePartitionColumns(ranges);
    }

    /**
     * 构建分区行
     *
     * @param columnSchemaList
     * @param rangeKey
     * @return
     */
    private static void buildRangeRow(List<ColumnSchema> columnSchemaList, String rangeKey, PartialRow row, String val) {
        for (ColumnSchema columnSchema : columnSchemaList) {
            if (rangeKey.equals(columnSchema.getName())) {
                switch (columnSchema.getType()) {
                    case BOOL:
                        row.addBoolean(rangeKey, Boolean.parseBoolean(val));
                        break;
                    case INT16:
                    case INT32:
                    case INT8:
                        row.addInt(rangeKey, Integer.parseInt(val));
                        break;
                    case INT64:
                        row.addLong(rangeKey, Long.parseLong(val));
                        break;
                    case STRING:
                        row.addString(rangeKey, val);
                        break;
                    case FLOAT:
                        row.addFloat(rangeKey, Float.parseFloat(val));
                        break;
                    case BINARY:
                        row.addBinary(rangeKey, val.getBytes(Charset.defaultCharset()));
                        break;
                    case DOUBLE:
                        row.addDouble(rangeKey, Double.parseDouble(val));
                        break;
                    case DECIMAL:
                        row.addDecimal(rangeKey, BigDecimal.valueOf(Long.parseLong(val)));
                        break;
                    case UNIXTIME_MICROS:
                        row.addTimestamp(rangeKey, new Timestamp(Long.parseLong(val)));
                        break;
                    default:
                        throw new RuntimeException("该kudu类型不支持!");
                }
            }
        }
    }

    public static List<String> getHashColumns(Map<String, String> tableProperties) {
        return Lists.newArrayList(tableProperties.get(KuduTableFactory.KUDU_HASH_COLS).split(","));
    }

    public static TableSchema getSchemaWithSqlTimestamp(TableSchema schema) {
        TableSchema.Builder builder = new TableSchema.Builder();
        TableSchemaUtils.getPhysicalSchema(schema).getTableColumns().forEach(
                tableColumn -> {
                    if (tableColumn.getType().getLogicalType() instanceof TimestampType) {
                        builder.field(tableColumn.getName(), tableColumn.getType().bridgedTo(Timestamp.class));
                    } else {
                        builder.field(tableColumn.getName(), tableColumn.getType());
                    }
                });
        return builder.build();
    }

    /**
     * Converts Flink Expression to KuduFilterInfo.
     */
    @Nullable
    public static Optional<KuduFilterInfo> toKuduFilterInfo(Expression predicate) {
        LOG.debug("predicate summary: [{}], class: [{}], children: [{}]",
                predicate.asSummaryString(), predicate.getClass(), predicate.getChildren());
        if (predicate instanceof CallExpression) {
            CallExpression callExpression = (CallExpression) predicate;
            FunctionDefinition functionDefinition = callExpression.getFunctionDefinition();
            List<Expression> children = callExpression.getChildren();
            if (children.size() == 1) {
                return convertUnaryIsNullExpression(functionDefinition, children);
            } else if (children.size() == 2 &&
                    !functionDefinition.equals(BuiltInFunctionDefinitions.OR)) {
                return convertBinaryComparison(functionDefinition, children);
            } else if (children.size() > 0 && functionDefinition.equals(BuiltInFunctionDefinitions.OR)) {
                return convertIsInExpression(children);
            }
        }
        return Optional.empty();
    }

    private static boolean isFieldReferenceExpression(Expression exp) {
        return exp instanceof FieldReferenceExpression;
    }

    private static boolean isValueLiteralExpression(Expression exp) {
        return exp instanceof ValueLiteralExpression;
    }

    private static Optional<KuduFilterInfo> convertUnaryIsNullExpression(
            FunctionDefinition functionDefinition, List<Expression> children) {
        FieldReferenceExpression fieldReferenceExpression;
        if (isFieldReferenceExpression(children.get(0))) {
            fieldReferenceExpression = (FieldReferenceExpression) children.get(0);
        } else {
            return Optional.empty();
        }
        // IS_NULL IS_NOT_NULL
        String columnName = fieldReferenceExpression.getName();
        KuduFilterInfo.Builder builder = KuduFilterInfo.Builder.create(columnName);
        if (functionDefinition.equals(BuiltInFunctionDefinitions.IS_NULL)) {
            return Optional.of(builder.isNull().build());
        } else if (functionDefinition.equals(BuiltInFunctionDefinitions.IS_NOT_NULL)) {
            return Optional.of(builder.isNotNull().build());
        }
        return Optional.empty();
    }

    private static Optional<KuduFilterInfo> convertBinaryComparison(
            FunctionDefinition functionDefinition, List<Expression> children) {
        FieldReferenceExpression fieldReferenceExpression;
        ValueLiteralExpression valueLiteralExpression;
        if (isFieldReferenceExpression(children.get(0)) &&
                isValueLiteralExpression(children.get(1))) {
            fieldReferenceExpression = (FieldReferenceExpression) children.get(0);
            valueLiteralExpression = (ValueLiteralExpression) children.get(1);
        } else if (isValueLiteralExpression(children.get(0)) &&
                isFieldReferenceExpression(children.get(1))) {
            fieldReferenceExpression = (FieldReferenceExpression) children.get(1);
            valueLiteralExpression = (ValueLiteralExpression) children.get(0);
        } else {
            return Optional.empty();
        }
        String columnName = fieldReferenceExpression.getName();
        Object value = extractValueLiteral(fieldReferenceExpression, valueLiteralExpression);
        if (value == null) {
            return Optional.empty();
        }
        KuduFilterInfo.Builder builder = KuduFilterInfo.Builder.create(columnName);
        // GREATER GREATER_EQUAL EQUAL LESS LESS_EQUAL
        if (functionDefinition.equals(BuiltInFunctionDefinitions.GREATER_THAN)) {
            return Optional.of(builder.greaterThan(value).build());
        } else if (functionDefinition.equals(BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL)) {
            return Optional.of(builder.greaterOrEqualTo(value).build());
        } else if (functionDefinition.equals(BuiltInFunctionDefinitions.EQUALS)) {
            return Optional.of(builder.equalTo(value).build());
        } else if (functionDefinition.equals(BuiltInFunctionDefinitions.LESS_THAN)) {
            return Optional.of(builder.lessThan(value).build());
        } else if (functionDefinition.equals(BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL)) {
            return Optional.of(builder.lessOrEqualTo(value).build());
        }
        return Optional.empty();
    }

    private static Optional<KuduFilterInfo> convertIsInExpression(List<Expression> children) {
        // IN operation will be: or(equals(field, value1), equals(field, value2), ...) in blink
        // For FilterType IS_IN, all internal CallExpression's function need to be equals and
        // fields need to be same
        List<Object> values = new ArrayList<>(children.size());
        String columnName = "";
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof CallExpression) {
                CallExpression callExpression = (CallExpression) children.get(i);
                FunctionDefinition functionDefinition = callExpression.getFunctionDefinition();
                List<Expression> subChildren = callExpression.getChildren();
                FieldReferenceExpression fieldReferenceExpression;
                ValueLiteralExpression valueLiteralExpression;
                if (functionDefinition.equals(BuiltInFunctionDefinitions.EQUALS) &&
                        subChildren.size() == 2 && isFieldReferenceExpression(subChildren.get(0)) &&
                        isValueLiteralExpression(subChildren.get(1))) {
                    fieldReferenceExpression = (FieldReferenceExpression) subChildren.get(0);
                    valueLiteralExpression = (ValueLiteralExpression) subChildren.get(1);
                    String fieldName = fieldReferenceExpression.getName();
                    if (i != 0 && !columnName.equals(fieldName)) {
                        return Optional.empty();
                    } else {
                        columnName = fieldName;
                    }
                    Object value = extractValueLiteral(fieldReferenceExpression,
                            valueLiteralExpression);
                    if (value == null) {
                        return Optional.empty();
                    }
                    values.add(i, value);
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        KuduFilterInfo.Builder builder = KuduFilterInfo.Builder.create(columnName);
        return Optional.of(builder.isIn(values).build());
    }

    private static Object extractValueLiteral(FieldReferenceExpression fieldReferenceExpression,
                                              ValueLiteralExpression valueLiteralExpression) {
        DataType fieldType = fieldReferenceExpression.getOutputDataType();
        return valueLiteralExpression.getValueAs(fieldType.getConversionClass()).orElse(null);
    }
}