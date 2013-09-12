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
import java.util.Map;
import parquet.Log;
import parquet.column.impl.ColumnWriteStoreImpl;
import parquet.hadoop.CodecFactory.BytesCompressor;
import parquet.hadoop.api.WriteSupport;
import parquet.io.ColumnIOFactory;
import parquet.io.MessageColumnIO;
import parquet.schema.MessageType;

import static java.lang.Math.max;
import static java.lang.Math.min;

class InternalParquetRecordWriter<T> {
  private static final Log LOG = Log.getLog(InternalParquetRecordWriter.class);

  private static final int MINIMUM_BUFFER_SIZE = 64 * 1024;

  private final ParquetFileWriter w;
  private final WriteSupport<T> writeSupport;
  private final MessageType schema;
  private final Map<String, String> extraMetaData;
  private final int blockSize;
  private final int pageSize;
  private final BytesCompressor compressor;
  private final boolean enableDictionary;
  private final boolean validating;

  private long recordCount = 0;
  private long recordCountForNextMemCheck = 100;

  private ColumnWriteStoreImpl store;
  private ColumnChunkPageWriteStore pageStore;



  /**
   *
   * @param w the file to write to
   * @param writeSupport the class to convert incoming records
   * @param schema the schema of the records
   * @param extraMetaData extra meta data to write in the footer of the file
   * @param blockSize the size of a block in the file (this will be approximate)
   * @param codec the codec used to compress
   */
  public InternalParquetRecordWriter(ParquetFileWriter w, WriteSupport<T> writeSupport,
      MessageType schema, Map<String, String> extraMetaData, int blockSize,
      int pageSize, BytesCompressor compressor, boolean enableDictionary, boolean validating) {
    if (writeSupport == null) {
      throw new NullPointerException("writeSupport");
    }
    this.w = w;
    this.writeSupport = writeSupport;
    this.schema = schema;
    this.extraMetaData = extraMetaData;
    this.blockSize = blockSize;
    this.pageSize = pageSize;
    this.compressor = compressor;
    this.enableDictionary = enableDictionary;
    this.validating = validating;
    initStore();
  }

  private void initStore() {
    // we don't want this number to be too small
    // ideally we divide the block equally across the columns
    // it is unlikely all columns are going to be the same size.
    int initialBlockBufferSize = max(MINIMUM_BUFFER_SIZE, blockSize / schema.getColumns().size() / 5);
    pageStore = new ColumnChunkPageWriteStore(compressor, schema, initialBlockBufferSize);
    // we don't want this number to be too small either
    // ideally, slightly bigger than the page size, but not bigger than the block buffer
    int initialPageBufferSize = max(MINIMUM_BUFFER_SIZE, min(pageSize + pageSize / 10, initialBlockBufferSize));
    store = new ColumnWriteStoreImpl(pageStore, pageSize, initialPageBufferSize, enableDictionary);
    MessageColumnIO columnIO = new ColumnIOFactory(validating).getColumnIO(schema);
    writeSupport.prepareForWrite(columnIO.getRecordWriter(store));
  }

  public void close() throws IOException, InterruptedException {
    flushStore();
    w.end(extraMetaData);
  }

  public void write(T value) throws IOException, InterruptedException {
    writeSupport.write(value);
    ++ recordCount;
    checkBlockSizeReached();
  }

  private void checkBlockSizeReached() throws IOException {
    if (recordCount >= recordCountForNextMemCheck) { // checking the memory size is relatively expensive, so let's not do it for every record.
      long memSize = store.memSize();
      if (memSize > blockSize) {
        LOG.info("mem size " + memSize + " > " + blockSize + ": flushing " + recordCount + " records to disk.");
        flushStore();
        initStore();
        recordCountForNextMemCheck = Math.max(100, recordCount / 2);
      } else {
        float recordSize = (float) memSize / recordCount;
        recordCountForNextMemCheck = Math.max(100, (recordCount + (long)(blockSize / recordSize)) / 2); // will check halfway
        LOG.debug("Checked mem at " + recordCount + " will check again at: " + recordCountForNextMemCheck);
      }
    }
  }

  private void flushStore()
      throws IOException {
    LOG.info("Flushing mem store to file. allocated memory: " + store.allocatedSize());
    w.startBlock(recordCount);
    store.flush();
    pageStore.flushToFileWriter(w);
    recordCount = 0;
    w.endBlock();
    store = null;
    pageStore = null;
  }
}