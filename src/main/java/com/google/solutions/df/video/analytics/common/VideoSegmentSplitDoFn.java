/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.solutions.df.video.analytics.common;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.splittabledofn.OffsetRangeTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VideoSegmentSplitDoFn extends DoFn<KV<String, ReadableFile>, KV<String, ByteString>> {
  public static final Logger LOG = LoggerFactory.getLogger(VideoSegmentSplitDoFn.class);
  private Integer chunkSize;

  public VideoSegmentSplitDoFn(Integer chunkSize, Integer keyRange) {
    this.chunkSize = chunkSize;
  }

  @ProcessElement
  public void processElement(ProcessContext c, RestrictionTracker<OffsetRange, Long> tracker)
      throws IOException {
    String fileName = c.element().getKey();
    try (SeekableByteChannel channel = getReader(c.element().getValue())) {
      ByteBuffer readBuffer = ByteBuffer.allocate(chunkSize);
      ByteString buffer = ByteString.EMPTY;
      for (long i = tracker.currentRestriction().getFrom(); tracker.tryClaim(i); ++i) {
        long startOffset = (i * chunkSize) - chunkSize;
        channel.position(startOffset);
        readBuffer = ByteBuffer.allocate(chunkSize);
        buffer = ByteString.EMPTY;
        channel.read(readBuffer);
        readBuffer.flip();
        buffer = ByteString.copyFrom(readBuffer);
        readBuffer.clear();
        LOG.info(
            "Current Restriction {}, Content Size{}", tracker.currentRestriction(), buffer.size());

        c.output(KV.of(fileName, buffer));
      }
    }
  }

  @GetInitialRestriction
  public OffsetRange getInitialRestriction(@Element KV<String, ReadableFile> file)
      throws IOException {
    long totalBytes = file.getValue().getMetadata().sizeBytes();
    long totalSplit = 0;
    if (totalBytes < chunkSize) {
      totalSplit = 2;
    } else {
      totalSplit = totalSplit + (totalBytes / chunkSize);
      long remaining = totalBytes % chunkSize;
      if (remaining > 0) {
        totalSplit = totalSplit + 2;
      }
    }
    LOG.info(
        "File Read Transform:ReadFile: Total Bytes {} for File {} -Initial Restriction range from 1 to: {}. Batch size of each chunk: {} ",
        totalBytes,
        file.getKey(),
        totalSplit,
        chunkSize);
    return new OffsetRange(1, totalSplit);
  }

  @SplitRestriction
  public void splitRestriction(
      @Element KV<String, ReadableFile> file,
      @Restriction OffsetRange range,
      OutputReceiver<OffsetRange> out) {
    for (final OffsetRange p : range.split(1, 1)) {
      out.output(p);
    }
  }

  @NewTracker
  public OffsetRangeTracker newTracker(@Restriction OffsetRange range) {
    return new OffsetRangeTracker(new OffsetRange(range.getFrom(), range.getTo()));
  }

  private static SeekableByteChannel getReader(ReadableFile eventFile) {
    SeekableByteChannel channel = null;
    try {
      channel = eventFile.openSeekable();
    } catch (IOException e) {
      LOG.error("Failed to Open File {}", e.getMessage());
      throw new RuntimeException(e);
    }
    return channel;
  }
}
