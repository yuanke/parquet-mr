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
package parquet.column.page;

/**
 * Reader for a sequence a page from a given column chunk
 *
 * @author Julien Le Dem
 *
 */
public interface PageReader {

 /**
  * @return the dictionary page in that chunk or null if none
  */
  DictionaryPage readDictionaryPage();

  /**
   * @return the total number of values in the column chunk
   */
  long getTotalValueCount();

  /**
   * @return the next page in that chunk or null if after the last page
   */
  Page readPage();
}
