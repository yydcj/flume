/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.channel.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.flume.chanel.file.proto.ProtosFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

final class EventQueueBackingStoreFileV3 extends EventQueueBackingStoreFile {
  private static final Logger LOG = LoggerFactory
      .getLogger(EventQueueBackingStoreFileV3.class);
  private final File metaDataFile;

  EventQueueBackingStoreFileV3(File checkpointFile, int capacity, String name)
      throws IOException {
    super(capacity, name, checkpointFile);
    Preconditions.checkArgument(capacity > 0,
        "capacity must be greater than 0 " + capacity);
    metaDataFile = Serialization.getMetaDataFile(checkpointFile);
    LOG.info("Starting up with " + checkpointFile + " and " + metaDataFile);
    if(metaDataFile.exists()) {
      FileInputStream inputStream = new FileInputStream(metaDataFile);
      try {
        LOG.info("Reading checkpoint metadata from " + metaDataFile);
        ProtosFactory.Checkpoint checkpoint =
            ProtosFactory.Checkpoint.parseDelimitedFrom(inputStream);
        int version = checkpoint.getVersion();
        Preconditions.checkState(version == getVersion(),
            "Invalid version: " + version + " " + name + ", expected "
                + getVersion());
        long logWriteOrderID = checkpoint.getWriteOrderID();
        if(logWriteOrderID != getCheckpointLogWriteOrderID()) {
          LOG.error("Checkpoint and Meta files have differing " +
              "logWriteOrderIDs " + getCheckpointLogWriteOrderID() + ", and "
              + logWriteOrderID);
          throw new IllegalStateException(
              "The last checkpoint was not completed correctly. Please delete "
                  + "the checkpoint files: " + checkpointFile + " and "
                  + Serialization.getMetaDataFile(checkpointFile)
                  + " to rebuild the checkpoint and start again. " + name);
        }
        WriteOrderOracle.setSeed(logWriteOrderID);
        setLogWriteOrderID(logWriteOrderID);
        setSize(checkpoint.getQueueSize());
        setHead(checkpoint.getQueueHead());
        for(ProtosFactory.ActiveLog activeLog : checkpoint.getActiveLogsList()) {
          Integer logFileID = activeLog.getLogFileID();
          Integer count = activeLog.getCount();
          logFileIDReferenceCounts.put(logFileID, new AtomicInteger(count));
        }
      } finally {
        try {
          inputStream.close();
        } catch (IOException e) {
          LOG.warn("Unable to close " + metaDataFile, e);
        }
      }
    } else {
      ProtosFactory.Checkpoint.Builder checkpointBuilder =
          ProtosFactory.Checkpoint.newBuilder();
      checkpointBuilder.setVersion(getVersion());
      checkpointBuilder.setQueueHead(getHead());
      checkpointBuilder.setQueueSize(getSize());
      checkpointBuilder.setWriteOrderID(getLogWriteOrderID());
      FileOutputStream outputStream = new FileOutputStream(metaDataFile);
      try {
        checkpointBuilder.build().writeDelimitedTo(outputStream);
        outputStream.getChannel().force(true);
      } finally {
        try {
          outputStream.close();
        } catch (IOException e) {
          LOG.warn("Unable to close " + metaDataFile, e);
        }
      }
    }
  }
  File getMetaDataFile() {
    return metaDataFile;
  }

  @Override
  protected int getVersion() {
    return Serialization.VERSION_3;
  }
  @Override
  protected void writeCheckpointMetaData() throws IOException {
    ProtosFactory.Checkpoint.Builder checkpointBuilder =
        ProtosFactory.Checkpoint.newBuilder();
    checkpointBuilder.setVersion(getVersion());
    checkpointBuilder.setQueueHead(getHead());
    checkpointBuilder.setQueueSize(getSize());
    checkpointBuilder.setWriteOrderID(getLogWriteOrderID());
    for(Integer logFileID : logFileIDReferenceCounts.keySet()) {
      int count = logFileIDReferenceCounts.get(logFileID).get();
      if(count != 0) {
         ProtosFactory.ActiveLog.Builder activeLogBuilder =
             ProtosFactory.ActiveLog.newBuilder();
         activeLogBuilder.setLogFileID(logFileID);
         activeLogBuilder.setCount(count);
         checkpointBuilder.addActiveLogs(activeLogBuilder.build());
      }
    }
    FileOutputStream outputStream = new FileOutputStream(metaDataFile);
    try {
      checkpointBuilder.build().writeDelimitedTo(outputStream);
      outputStream.getChannel().force(true);
    } finally {
      try {
        outputStream.close();
      } catch (IOException e) {
        LOG.warn("Unable to close " + metaDataFile, e);
      }
    }
  }

  static void upgrade(EventQueueBackingStoreFileV2 backingStoreV2,
      File checkpointFile, File metaDataFile)
          throws IOException {

    int head = backingStoreV2.getHead();
    int size = backingStoreV2.getSize();
    long writeOrderID = backingStoreV2.getLogWriteOrderID();
    Map<Integer, AtomicInteger> referenceCounts =
        backingStoreV2.logFileIDReferenceCounts;

    ProtosFactory.Checkpoint.Builder checkpointBuilder =
        ProtosFactory.Checkpoint.newBuilder();
    checkpointBuilder.setVersion(Serialization.VERSION_3);
    checkpointBuilder.setQueueHead(head);
    checkpointBuilder.setQueueSize(size);
    checkpointBuilder.setWriteOrderID(writeOrderID);
    for(Integer logFileID : referenceCounts.keySet()) {
      int count = referenceCounts.get(logFileID).get();
      if(count > 0) {
         ProtosFactory.ActiveLog.Builder activeLogBuilder =
             ProtosFactory.ActiveLog.newBuilder();
         activeLogBuilder.setLogFileID(logFileID);
         activeLogBuilder.setCount(count);
         checkpointBuilder.addActiveLogs(activeLogBuilder.build());
      }
    }
    FileOutputStream outputStream = new FileOutputStream(metaDataFile);
    try {
      checkpointBuilder.build().writeDelimitedTo(outputStream);
      outputStream.getChannel().force(true);
    } finally {
      try {
        outputStream.close();
      } catch (IOException e) {
        LOG.warn("Unable to close " + metaDataFile, e);
      }
    }
    RandomAccessFile checkpointFileHandle =
        new RandomAccessFile(checkpointFile, "rw");
    try {
      checkpointFileHandle.seek(INDEX_VERSION * Serialization.SIZE_OF_LONG);
      checkpointFileHandle.writeLong(Serialization.VERSION_3);
      checkpointFileHandle.getChannel().force(true);
    } finally {
      try {
        checkpointFileHandle.close();
      } catch (IOException e) {
        LOG.warn("Unable to close " + checkpointFile, e);
      }
    }
  }
}