/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.hadoop;

import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskInputOutputContext;
import parquet.filter.UnboundRecordFilter;
import parquet.hadoop.api.ReadSupport;
import parquet.hadoop.util.BenchmarkCounter;
import parquet.hadoop.util.ContextUtil;
import parquet.schema.MessageTypeParser;

/**
 * Reads the records from a block of a Parquet file
 *
 * @see ParquetInputFormat
 *
 * @author Julien Le Dem
 *
 * @param <T> type of the materialized records
 */
public class ParquetRecordReader<T> extends RecordReader<Void, T> {

  private InternalParquetRecordReader<T> internalReader;

  /**
   * @param readSupport Object which helps reads files of the given type, e.g. Thrift, Avro.
   */
  public ParquetRecordReader(ReadSupport<T> readSupport) {
    this(readSupport, null);
  }

  /**
   * @param readSupport Object which helps reads files of the given type, e.g. Thrift, Avro.
   * @param filter Optional filter for only returning matching records.
   */
  public ParquetRecordReader(ReadSupport<T> readSupport, UnboundRecordFilter filter) {
    internalReader = new InternalParquetRecordReader<T>(readSupport, filter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws IOException {
    internalReader.close();
  }

  /**
   * always returns null
   */
  @Override
  public Void getCurrentKey() throws IOException, InterruptedException {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T getCurrentValue() throws IOException,
  InterruptedException {
    return internalReader.getCurrentValue();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getProgress() throws IOException, InterruptedException {
    return internalReader.getProgress();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void initialize(InputSplit inputSplit, TaskAttemptContext context)
      throws IOException, InterruptedException {
    if (context instanceof TaskInputOutputContext<?, ?, ?, ?>) {
      BenchmarkCounter.initCounterFromContext((TaskInputOutputContext<?, ?, ?, ?>) context);
    }
    initialize(inputSplit, ContextUtil.getConfiguration(context));
  }

  public void initialize(InputSplit inputSplit, Configuration configuration)
      throws IOException, InterruptedException {
    ParquetInputSplit split = (ParquetInputSplit) inputSplit;
    internalReader.initialize(
        MessageTypeParser.parseMessageType(split.getRequestedSchema()),
        MessageTypeParser.parseMessageType(split.getFileSchema()),
        split.getExtraMetadata(), split.getReadSupportMetadata(), split.getPath(),
        split.getBlocks(), configuration);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    return internalReader.nextKeyValue();
  }
}