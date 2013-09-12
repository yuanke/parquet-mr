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
package parquet.thrift;
import java.util.HashMap;
import java.util.Map;

import org.apache.thrift.TBase;

import parquet.Log;
import parquet.hadoop.BadConfigurationException;
import parquet.thrift.struct.ThriftType;
import parquet.thrift.struct.ThriftType.StructType;


public class ThriftMetaData {
  private static final Log LOG = Log.getLog(ThriftMetaData.class);

  private static final String THRIFT_CLASS = "thrift.class";
  private static final String THRIFT_DESCRIPTOR = "thrift.descriptor";
  private Class<?> thriftClass;
  private final String thriftClassName;
  private final StructType descriptor;

  public ThriftMetaData(String thriftClassName, StructType descriptor) {
    this.thriftClassName = thriftClassName;
    this.descriptor = descriptor;
  }

  /**
   * Get the Thrift Class encoded in the metadata.
   * @return Thrift Class encoded in the metadata.
   * @throws BadConfigurationException if the encoded class does not
   * extend TBase or is not available in the current classloader.
   */
  public Class<?> getThriftClass() {
    if (thriftClass == null) {
      try {
        thriftClass = Class.forName(thriftClassName);
        if (!TBase.class.isAssignableFrom(thriftClass)) {
          throw new BadConfigurationException("Provided class " + thriftClassName + " does not extend TBase");
        }
      } catch (ClassNotFoundException e) {
        throw new BadConfigurationException("Could not instantiate thrift class " + thriftClassName, e);
      }
    }
    return thriftClass;
  }

  public StructType getDescriptor() {
    return descriptor;
  }

  /**
   * Reads ThriftMetadata from the parquet file footer.
   *
   *
   * @param extraMetaData  extraMetaData field of the parquet footer
   * @return
   */
  public static ThriftMetaData fromExtraMetaData(
      Map<String, String> extraMetaData) {
    final String thriftClassName = extraMetaData.get(THRIFT_CLASS);
    final String thriftDescriptorString = extraMetaData.get(THRIFT_DESCRIPTOR);
    if (thriftClassName == null && thriftDescriptorString == null) {
      return null;
    }
    final StructType descriptor;
    try {
      descriptor = (StructType)ThriftType.fromJSON(thriftDescriptorString);
    } catch (RuntimeException e) {
      throw new BadConfigurationException("Could not read the thrift descriptor " + thriftDescriptorString, e);
    }
    return new ThriftMetaData(thriftClassName, descriptor);
  }

  public Map<String, String> toExtraMetaData() {
    final Map<String, String> map = new HashMap<String, String>();
    map.put(THRIFT_CLASS, getThriftClass().getName());
    map.put(THRIFT_DESCRIPTOR, descriptor.toJSON());
    return map;
  }

}
