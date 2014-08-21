/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.iterators;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import io.fluo.core.impl.DelLockValue;
import io.fluo.core.impl.WriteValue;

import io.fluo.core.util.ColumnUtil;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;

/**
 * 
 */
public class PrewriteIterator implements SortedKeyValueIterator<Key,Value> {
  private static final String TIMESTAMP_OPT = "timestampOpt";
  private static final String CHECK_ACK_OPT = "checkAckOpt";
  
  private SortedKeyValueIterator<Key,Value> source;
  private long snaptime;
  
  boolean hasTop = false;
  boolean checkAck = false;
  
  public static void setSnaptime(IteratorSetting cfg, long time) {
    if (time < 0 || (ColumnUtil.PREFIX_MASK & time) != 0) {
      throw new IllegalArgumentException();
    }
    cfg.addOption(TIMESTAMP_OPT, time + "");
  }
  
  public static void enableAckCheck(IteratorSetting cfg) {
    cfg.addOption(CHECK_ACK_OPT, "true");
  }
  
  public void init(SortedKeyValueIterator<Key,Value> source, Map<String,String> options, IteratorEnvironment env) throws IOException {
    this.source = source;
    this.snaptime = Long.parseLong(options.get(TIMESTAMP_OPT));
    if (options.containsKey(CHECK_ACK_OPT)) {
      this.checkAck = Boolean.parseBoolean(options.get(CHECK_ACK_OPT));
    }
  }
  
  public boolean hasTop() {
    return hasTop && source.hasTop();
  }
  
  public void next() throws IOException {
    hasTop = false;
  }
  
  public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
    IteratorUtil.maximizeStartKeyTimeStamp(range);

    source.seek(range, columnFamilies, inclusive);
    
    Key curCol = new Key();
    
    if (source.hasTop()) {
      curCol.set(source.getTopKey());
      
      // TODO can this optimization cause problems?
      if (!curCol.equals(range.getStartKey(), PartialKey.ROW_COLFAM_COLQUAL_COLVIS)) {
        return;
      }
    }
    
    long invalidationTime = -1;
    long firstWrite = -1;
    
    hasTop = false;
    while (source.hasTop() && curCol.equals(source.getTopKey(), PartialKey.ROW_COLFAM_COLQUAL_COLVIS)) {
      long colType = source.getTopKey().getTimestamp() & ColumnUtil.PREFIX_MASK;
      long ts = source.getTopKey().getTimestamp() & ColumnUtil.TIMESTAMP_MASK;
      
      if (colType == ColumnUtil.TX_DONE_PREFIX) {
        
      } else if (colType == ColumnUtil.WRITE_PREFIX) {
        
        long timePtr = WriteValue.getTimestamp(source.getTopValue().get());
        
        if (timePtr > invalidationTime)
          invalidationTime = timePtr;
        
        if (firstWrite == -1) {
          firstWrite = ts;
        }
        
        if (ts >= snaptime) {
          hasTop = true;
          return;
        }
        
      } else if (colType == ColumnUtil.DEL_LOCK_PREFIX) {
        long timePtr = DelLockValue.getTimestamp(source.getTopValue().get());
        
        if (timePtr > invalidationTime) {
          invalidationTime = timePtr;
          
          // this delete marker will hide locks, so can not let a lock be written before it
          // TODO need unit test for this iterator... for this case
          if (timePtr >= snaptime) {
            hasTop = true;
            return;
          }
        }
      } else if (colType == ColumnUtil.LOCK_PREFIX) {
        if (ts > invalidationTime) {
          // nothing supersedes this lock, therefore the column is locked
          hasTop = true;
          return;
        }
      } else if (colType == ColumnUtil.DATA_PREFIX) {
        // can stop looking
        return;
      } else if (colType == ColumnUtil.ACK_PREFIX) {
        if (checkAck && ts >= firstWrite) {
          hasTop = true;
          return;
        }
      } else {
        throw new IllegalArgumentException();
      }
      
      source.next();
    }
  }
  
  public Key getTopKey() {
    return source.getTopKey();
  }
  
  public Value getTopValue() {
    return source.getTopValue();
  }
  
  public SortedKeyValueIterator<Key,Value> deepCopy(IteratorEnvironment env) {
    // TODO Auto-generated method stub
    return null;
  }
}