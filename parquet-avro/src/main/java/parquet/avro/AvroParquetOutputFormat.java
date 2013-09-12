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
package parquet.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.mapreduce.Job;
import parquet.avro.AvroWriteSupport;
import parquet.hadoop.ParquetOutputFormat;
import parquet.hadoop.util.ContextUtil;

/**
 * A Hadoop {@link org.apache.hadoop.mapreduce.OutputFormat} for Parquet files.
 */
public class AvroParquetOutputFormat extends ParquetOutputFormat<IndexedRecord> {

  public static void setSchema(Job job, Schema schema) {
    AvroWriteSupport.setSchema(ContextUtil.getConfiguration(job), schema);
  }

  public AvroParquetOutputFormat() {
    super(new AvroWriteSupport());
  }

}
