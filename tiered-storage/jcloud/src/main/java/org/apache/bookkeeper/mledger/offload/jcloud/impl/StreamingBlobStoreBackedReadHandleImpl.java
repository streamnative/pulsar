/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bookkeeper.mledger.offload.jcloud.impl;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.val;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.api.LastConfirmedAndEntry;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.LedgerMetadata;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.client.impl.LedgerEntriesImpl;
import org.apache.bookkeeper.client.impl.LedgerEntryImpl;
import org.apache.bookkeeper.mledger.offload.jcloud.BackedInputStream;
import org.apache.bookkeeper.mledger.offload.jcloud.StreamingOffloadIndexBlock;
import org.apache.bookkeeper.mledger.offload.jcloud.StreamingOffloadIndexBlockBuilder;
import org.apache.bookkeeper.mledger.offload.jcloud.impl.DataBlockUtils.VersionCheck;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingBlobStoreBackedReadHandleImpl implements ReadHandle {
    private static final Logger log = LoggerFactory.getLogger(StreamingBlobStoreBackedReadHandleImpl.class);

    private final long ledgerId;
    private final List<StreamingOffloadIndexBlock> indice;
    private final List<BackedInputStream> inputStreams;
    private final List<DataInputStream> dataStreams;
    private final ExecutorService executor;

    class GroupedReader {
        long ledgerId;
        long firstEntry;
        long lastEntry;
        StreamingOffloadIndexBlock index;
        BackedInputStream inputStream;
        DataInputStream dataStream;

        public GroupedReader(long ledgerId, long firstEntry, long lastEntry,
                             StreamingOffloadIndexBlock index,
                             BackedInputStream inputStream, DataInputStream dataStream) {
            this.ledgerId = ledgerId;
            this.firstEntry = firstEntry;
            this.lastEntry = lastEntry;
            this.index = index;
            this.inputStream = inputStream;
            this.dataStream = dataStream;
        }
    }

    private StreamingBlobStoreBackedReadHandleImpl(long ledgerId, List<StreamingOffloadIndexBlock> indice,
                                                   List<BackedInputStream> inputStreams,
                                                   ExecutorService executor) {
        this.ledgerId = ledgerId;
        this.indice = indice;
        this.inputStreams = inputStreams;
        this.dataStreams = new LinkedList<>();
        for (BackedInputStream inputStream : inputStreams) {
            dataStreams.add(new DataInputStream(inputStream));
        }
        this.executor = executor;
    }

    @Override
    public long getId() {
        return ledgerId;
    }

    @Override
    public LedgerMetadata getLedgerMetadata() {
        return indice.get(0).getLedgerMetadata().get(ledgerId);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                for (StreamingOffloadIndexBlock indexBlock : indice) {
                    indexBlock.close();
                }
                for (BackedInputStream inputStream : inputStreams) {
                    inputStream.close();
                }
                promise.complete(null);
            } catch (IOException t) {
                promise.completeExceptionally(t);
            }
        });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readAsync(long firstEntry, long lastEntry) {
        log.debug("Ledger {}: reading {} - {}", getId(), firstEntry, lastEntry);
        CompletableFuture<LedgerEntries> promise = new CompletableFuture<>();
        if (firstEntry > lastEntry
                || firstEntry < 0
                || lastEntry > getLastAddConfirmed()) {
            promise.completeExceptionally(new BKException.BKIncorrectParameterException());
            return promise;
        }
        executor.submit(() -> {
            List<LedgerEntry> entries = new ArrayList<LedgerEntry>();
            List<GroupedReader> groupedReaders = null;
            try {
                groupedReaders = getGroupedReader(firstEntry, lastEntry);
            } catch (Exception e) {
                promise.completeExceptionally(e);
                return;
            }

            for (GroupedReader groupedReader : groupedReaders) {
                long entriesToRead = (groupedReader.lastEntry - groupedReader.firstEntry) + 1;
                long nextExpectedId = groupedReader.firstEntry;
                try {
                    while (entriesToRead > 0) {
                        int length = groupedReader.dataStream.readInt();
                        if (length < 0) { // hit padding or new block
                            groupedReader.inputStream
                                    .seek(groupedReader.index.getIndexEntryForEntry(ledgerId, nextExpectedId)
                                            .getDataOffset());
                            continue;
                        }
                        long entryId = groupedReader.dataStream.readLong();

                        if (entryId == nextExpectedId) {
                            ByteBuf buf = PulsarByteBufAllocator.DEFAULT.buffer(length, length);
                            entries.add(LedgerEntryImpl.create(ledgerId, entryId, length, buf));
                            int toWrite = length;
                            while (toWrite > 0) {
                                toWrite -= buf.writeBytes(groupedReader.dataStream, toWrite);
                            }
                            entriesToRead--;
                            nextExpectedId++;
                        } else if (entryId > nextExpectedId) {
                            groupedReader.inputStream
                                    .seek(groupedReader.index.getIndexEntryForEntry(ledgerId, nextExpectedId)
                                            .getDataOffset());
                            continue;
                        } else if (entryId < nextExpectedId
                                && !groupedReader.index.getIndexEntryForEntry(ledgerId, nextExpectedId).equals(
                                groupedReader.index.getIndexEntryForEntry(ledgerId, entryId))) {
                            groupedReader.inputStream
                                    .seek(groupedReader.index.getIndexEntryForEntry(ledgerId, nextExpectedId)
                                            .getDataOffset());
                            continue;
                        } else if (entryId > groupedReader.lastEntry) {
                            log.info("Expected to read {}, but read {}, which is greater than last entry {}",
                                    nextExpectedId, entryId, groupedReader.lastEntry);
                            throw new BKException.BKUnexpectedConditionException();
                        } else {
                            val skipped = groupedReader.inputStream.skip(length);
                            log.info("Skipped {} bytes.", skipped);
                        }
                    }
                } catch (Throwable t) {
                    promise.completeExceptionally(t);
                    entries.forEach(LedgerEntry::close);
                }

                promise.complete(LedgerEntriesImpl.create(entries));
            }
        });
        return promise;
    }

    private List<GroupedReader> getGroupedReader(long firstEntry, long lastEntry) throws Exception {
        List<GroupedReader> groupedReaders = new LinkedList<>();
        for (int i = indice.size() - 1; i >= 0 && firstEntry <= lastEntry; i--) {
            final StreamingOffloadIndexBlock index = indice.get(i);
            final long startEntryId = index.getStartEntryId(ledgerId);
            if (startEntryId > lastEntry) {
                throw new Exception("should not happen");
            } else if (startEntryId > firstEntry) {
                groupedReaders.add(new GroupedReader(ledgerId, startEntryId, lastEntry, index, inputStreams.get(i),
                        dataStreams.get(i)));
                lastEntry = startEntryId - 1;
            } else {
                groupedReaders.add(new GroupedReader(ledgerId, startEntryId, lastEntry, index, inputStreams.get(i),
                        dataStreams.get(i)));
            }
        }
        Preconditions.checkArgument(firstEntry - lastEntry == 1);
        return groupedReaders;
    }

    @Override
    public CompletableFuture<LedgerEntries> readUnconfirmedAsync(long firstEntry, long lastEntry) {
        return readAsync(firstEntry, lastEntry);
    }

    @Override
    public CompletableFuture<Long> readLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public CompletableFuture<Long> tryReadLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public long getLastAddConfirmed() {
        return getLedgerMetadata().getLastEntryId();
    }

    @Override
    public long getLength() {
        return getLedgerMetadata().getLength();
    }

    @Override
    public boolean isClosed() {
        return getLedgerMetadata().isClosed();
    }

    @Override
    public CompletableFuture<LastConfirmedAndEntry> readLastAddConfirmedAndEntryAsync(long entryId,
                                                                                      long timeOutInMillis,
                                                                                      boolean parallel) {
        CompletableFuture<LastConfirmedAndEntry> promise = new CompletableFuture<>();
        promise.completeExceptionally(new UnsupportedOperationException());
        return promise;
    }

    public static ReadHandle open(ScheduledExecutorService executor,
                                  BlobStore blobStore, String bucket, List<String> keys, List<String> indexKeys,
                                  VersionCheck versionCheck,
                                  long ledgerId, int readBufferSize)
            throws IOException {
        List<BackedInputStream> inputStreams = new LinkedList<>();
        List<StreamingOffloadIndexBlock> indice = new LinkedList<>();
        for (int i = 0; i < indexKeys.size(); i++) {
            String indexKey = indexKeys.get(i);
            String key = keys.get(i);
            Blob blob = blobStore.getBlob(bucket, indexKey);
            versionCheck.check(indexKey, blob);
            StreamingOffloadIndexBlockBuilder indexBuilder = StreamingOffloadIndexBlockBuilder.create();
            StreamingOffloadIndexBlock index = indexBuilder.fromStream(blob.getPayload().openStream());

            BackedInputStream inputStream = new BlobStoreBackedInputStreamImpl(blobStore, bucket, key,
                    versionCheck,
                    index.getDataObjectLength(),
                    readBufferSize);
            inputStreams.add(inputStream);
            indice.add(index);
        }
        return new StreamingBlobStoreBackedReadHandleImpl(ledgerId, indice, inputStreams, executor);
    }
}
