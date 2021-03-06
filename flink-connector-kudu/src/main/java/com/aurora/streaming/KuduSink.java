package com.aurora.streaming;

import com.aurora.connector.KuduTableInfo;
import com.aurora.connector.failure.DefaultKuduFailureHandler;
import com.aurora.connector.failure.KuduFailureHandler;
import com.aurora.connector.writer.KuduOperationMapper;
import com.aurora.connector.writer.KuduWriter;
import com.aurora.connector.writer.KuduWriterConfig;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * @author lj.michale
 * @description 根据接收的元素执行Kudu操作的流式sink。
 * Streaming Sink that executes Kudu operations based on the incoming elements.
 * 目标的kudu table定义在KuduTableInfo对象中包含一些表的创建的参数
 * The target Kudu table is defined in the {@link KuduTableInfo} object together with parameters for table
 * creation in case the table does not exist.
 * <p>
 * 接收元素被映射在kudu表的operations使用提供的KuduOperationMapper  当写入数据失败是通过KuduFailureHandler实例来处理
 * Incoming records are mapped to Kudu table operations using the provided {@link KuduOperationMapper} logic. While
 * failures resulting from the operations are handled by the {@link KuduFailureHandler} instance.
 * <p>
 * <p>
 * 实现flink的RichSinkFunction和CheckpointedFunction
 *
 * @param <IN> Type of the input records
 * @date 2021-07-07
 */
@PublicEvolving
public class KuduSink<IN> extends RichSinkFunction<IN> implements CheckpointedFunction {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** kudu table 信息 */
    private final KuduTableInfo tableInfo;
    /** kudu 写入配置包含master、session刷新模式 */
    private final KuduWriterConfig writerConfig;
    /** 失败处理器 */
    private final KuduFailureHandler failureHandler;
    /** kudu操作映射，主要包含insert、update、delete、query等等 */
    private final KuduOperationMapper<IN> opsMapper;
    /** kudu写入序列化器 */
    private transient KuduWriter kuduWriter;

    /**
     * Creates a new {@link KuduSink} that will execute operations against the specified Kudu table (defined in {@link KuduTableInfo})
     * for the incoming stream elements.
     *
     * @param writerConfig Writer configuration
     * @param tableInfo    Table information for the target table
     * @param opsMapper    Mapping logic from inputs to Kudu operations
     */
    public KuduSink(KuduWriterConfig writerConfig, KuduTableInfo tableInfo, KuduOperationMapper<IN> opsMapper) {
        this(writerConfig, tableInfo, opsMapper, new DefaultKuduFailureHandler());
    }

    /**
     * Creates a new {@link KuduSink} that will execute operations against the specified Kudu table (defined in {@link KuduTableInfo})
     * for the incoming stream elements.
     *
     * @param writerConfig   Writer configuration
     * @param tableInfo      Table information for the target table
     * @param opsMapper      Mapping logic from inputs to Kudu operations
     * @param failureHandler Custom failure handler instance
     */
    public KuduSink(KuduWriterConfig writerConfig, KuduTableInfo tableInfo, KuduOperationMapper<IN> opsMapper, KuduFailureHandler failureHandler) {
        this.tableInfo = checkNotNull(tableInfo, "tableInfo could not be null");
        this.writerConfig = checkNotNull(writerConfig, "config could not be null");
        this.opsMapper = checkNotNull(opsMapper, "opsMapper could not be null");
        this.failureHandler = checkNotNull(failureHandler, "failureHandler could not be null");
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        kuduWriter = new KuduWriter(tableInfo, writerConfig, opsMapper, failureHandler);
    }

    @Override
    public void invoke(IN value, Context context) throws Exception {
        try {
            kuduWriter.write(value);
        } catch (ClassCastException e) {
            failureHandler.onTypeMismatch(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (kuduWriter != null) {
            kuduWriter.close();
        }
    }

    @Override
    public void snapshotState(FunctionSnapshotContext functionSnapshotContext) throws Exception {
        kuduWriter.flushAndCheckErrors();
    }

    @Override
    public void initializeState(FunctionInitializationContext functionInitializationContext) throws Exception {
    }

}