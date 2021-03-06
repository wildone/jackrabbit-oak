/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.mongomk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.jackrabbit.mk.api.MicroKernelException;

/**
 * Emulates a MongoDB store (possibly consisting of multiple shards and
 * replicas).
 */
public class MemoryDocumentStore implements DocumentStore {

    /**
     * The 'nodes' collection.
     */
    private ConcurrentSkipListMap<String, NodeDocument> nodes =
            new ConcurrentSkipListMap<String, NodeDocument>();

    /**
     * The 'clusterNodes' collection.
     */
    private ConcurrentSkipListMap<String, Document> clusterNodes =
            new ConcurrentSkipListMap<String, Document>();

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Comparator for maps with {@link Revision} keys. The maps are ordered
     * descending, newest revisions first!
     */
    private final Comparator<Revision> comparator = Collections.reverseOrder(new StableRevisionComparator());

    @Override
    public <T extends Document> T find(Collection<T> collection, String key, int maxCacheAge) {
        return find(collection, key);
    }
    
    @Override
    public <T extends Document> T find(Collection<T> collection, String key) {
        Lock lock = rwLock.readLock();
        lock.lock();
        try {
            ConcurrentSkipListMap<String, T> map = getMap(collection);
            return map.get(key);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    @Nonnull
    public <T extends Document> List<T> query(Collection<T> collection,
                                String fromKey,
                                String toKey,
                                int limit) {
        return query(collection, fromKey, toKey, null, 0, limit);
    }
    
    @Override
    @Nonnull
    public <T extends Document> List<T> query(Collection<T> collection,
                                String fromKey,
                                String toKey,
                                String indexedProperty,
                                long startValue,
                                int limit) {
        Lock lock = rwLock.readLock();
        lock.lock();
        try {
            ConcurrentSkipListMap<String, T> map = getMap(collection);
            ConcurrentNavigableMap<String, T> sub = map.subMap(fromKey + "\0", toKey);
            ArrayList<T> list = new ArrayList<T>();
            for (T doc : sub.values()) {
                if (indexedProperty != null) {
                    Long value = (Long) doc.get(indexedProperty);
                    if (value < startValue) {
                        continue;
                    }
                }
                list.add(doc);
                if (list.size() >= limit) {
                    break;
                }
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T extends Document> void remove(Collection<T> collection, String path) {
        Lock lock = rwLock.writeLock();
        lock.lock();
        try {
            getMap(collection).remove(path);
        } finally {
            lock.unlock();
        }
    }

    @CheckForNull
    @Override
    public <T extends Document> T createOrUpdate(Collection<T> collection, UpdateOp update)
            throws MicroKernelException {
        return internalCreateOrUpdate(collection, update, false);
    }

    @Override
    public <T extends Document> T findAndUpdate(Collection<T> collection, UpdateOp update)
            throws MicroKernelException {
        return internalCreateOrUpdate(collection, update, true);
    }

    /**
     * Get the in-memory map for this collection.
     *
     * @param collection the collection
     * @return the map
     */
    @SuppressWarnings("unchecked")
    private <T extends Document> ConcurrentSkipListMap<String, T> getMap(Collection<T> collection) {
        if (collection == Collection.NODES) {
            return (ConcurrentSkipListMap<String, T>) nodes;
        } else if (collection == Collection.CLUSTER_NODES) {
            return (ConcurrentSkipListMap<String, T>) clusterNodes;
        } else {
            throw new IllegalArgumentException(
                    "Unknown collection: " + collection.toString());
        }
    }

    @CheckForNull
    private <T extends Document> T internalCreateOrUpdate(Collection<T> collection,
                                                          UpdateOp update,
                                                          boolean checkConditions) {
        ConcurrentSkipListMap<String, T> map = getMap(collection);
        T oldDoc;

        Lock lock = rwLock.writeLock();
        lock.lock();
        try {
            // get the node if it's there
            oldDoc = map.get(update.id);

            T doc = collection.newDocument(this);
            if (oldDoc == null) {
                if (!update.isNew()) {
                    throw new MicroKernelException("Document does not exist: " + update.id);
                }
            } else {
                oldDoc.deepCopy(doc);
            }
            if (checkConditions && ! UpdateUtils.checkConditions(doc, update)) {
                return null;
            }
            // update the document
            UpdateUtils.applyChanges(doc, update, comparator);
            doc.seal();
            map.put(update.id, doc);
            return oldDoc;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T extends Document> boolean create(Collection<T> collection,
                                               List<UpdateOp> updateOps) {
        Lock lock = rwLock.writeLock();
        lock.lock();
        try {
            ConcurrentSkipListMap<String, T> map = getMap(collection);
            for (UpdateOp op : updateOps) {
                if (map.containsKey(op.id)) {
                    return false;
                }
            }
            for (UpdateOp op : updateOps) {
                internalCreateOrUpdate(collection, op, false);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T extends Document> void update(Collection<T> collection,
                                            List<String> keys,
                                            UpdateOp updateOp) {
        Lock lock = rwLock.writeLock();
        lock.lock();
        try {
            ConcurrentSkipListMap<String, T> map = getMap(collection);
            for (String key : keys) {
                if (!map.containsKey(key)) {
                    continue;
                }
                internalCreateOrUpdate(collection, new UpdateOp(key, updateOp), true);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        StringBuilder buff = new StringBuilder();
        buff.append("Nodes:\n");
        for (String p : nodes.keySet()) {
            buff.append("Path: ").append(p).append('\n');
            NodeDocument doc = nodes.get(p);
            for (String prop : doc.keySet()) {
                buff.append(prop).append('=').append(doc.get(prop)).append('\n');
            }
            buff.append("\n");
        }
        return buff.toString();
    }

    @Override
    public void invalidateCache() {
        // there is no cache, so nothing to invalidate
    }

    @Override
    public void dispose() {
        // ignore
    }

    @Override
    public <T extends Document> T getIfCached(Collection<T> collection, String key) {
        return find(collection, key);
    }

    @Override
    public <T extends Document> void invalidateCache(Collection<T> collection, String key) {
        // ignore
    }

}