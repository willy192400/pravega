/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.server.bucket;

import io.pravega.client.ClientConfig;
import io.pravega.client.netty.impl.ConnectionFactoryImpl;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.impl.StreamImpl;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.tracing.RequestTracker;
import io.pravega.controller.mocks.SegmentHelperMock;
import io.pravega.controller.server.SegmentHelper;
import io.pravega.controller.server.rpc.auth.AuthHelper;
import io.pravega.controller.store.host.HostControllerStore;
import io.pravega.controller.store.host.HostStoreFactory;
import io.pravega.controller.store.host.impl.HostMonitorConfigImpl;
import io.pravega.controller.store.stream.BucketStore;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.store.task.TaskMetadataStore;
import io.pravega.controller.store.task.TaskStoreFactory;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.controller.util.RetryHelper;
import io.pravega.test.common.AssertExtensions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public abstract class BucketServiceTest {
    StreamMetadataStore streamMetadataStore;
    BucketStore bucketStore;
    BucketManager service;
    ScheduledExecutorService executor;
    StreamMetadataTasks streamMetadataTasks;
    private ConnectionFactoryImpl connectionFactory;
    private String hostId;
    private RequestTracker requestTracker = new RequestTracker(true);

    @Before
    public void setup() throws Exception {
        executor = Executors.newScheduledThreadPool(10);
        hostId = UUID.randomUUID().toString();

        streamMetadataStore = createStreamStore(executor);
        bucketStore = createBucketStore(3);
        
        TaskMetadataStore taskMetadataStore = TaskStoreFactory.createInMemoryStore(executor);
        HostControllerStore hostStore = HostStoreFactory.createInMemoryStore(HostMonitorConfigImpl.dummyConfig());

        SegmentHelper segmentHelper = SegmentHelperMock.getSegmentHelperMock();
        connectionFactory = new ConnectionFactoryImpl(ClientConfig.builder().build());

        streamMetadataTasks = new StreamMetadataTasks(streamMetadataStore, bucketStore, hostStore, taskMetadataStore, 
                segmentHelper, executor, hostId, connectionFactory, AuthHelper.getDisabledAuthHelper(), requestTracker);
        BucketServiceFactory bucketStoreFactory = new BucketServiceFactory(hostId, bucketStore, 2, executor);
        PeriodicRetention periodicRetention = new PeriodicRetention(streamMetadataStore, streamMetadataTasks, executor, requestTracker);
        service = bucketStoreFactory.createRetentionService(Duration.ofMillis(5), periodicRetention::retention);
        service.startAsync();
        service.awaitRunning();
    }

    @After
    public void tearDown() throws Exception {
        streamMetadataTasks.close();
        service.stopAsync();
        service.awaitTerminated();
        connectionFactory.close();
        ExecutorServiceHelpers.shutdown(executor);
    }

    abstract StreamMetadataStore createStreamStore(Executor executor);

    abstract BucketStore createBucketStore(int bucketCount);

    @Test(timeout = 10000)
    public void testRetentionService() {
        Map<Integer, BucketService> bucketServices = service.getBucketServices();
                                          
        assertNotNull(bucketServices);
        assertEquals(3, bucketServices.size());
        assertTrue(service.takeBucketOwnership(0, hostId, executor).join());
        assertTrue(service.takeBucketOwnership(1, hostId, executor).join());
        assertTrue(service.takeBucketOwnership(2, hostId, executor).join());
        AssertExtensions.assertThrows("", () -> service.takeBucketOwnership(3, hostId, executor).join(),
                e -> e instanceof IllegalArgumentException);
        service.tryTakeOwnership(0).join();

        String scope = "scope";
        String streamName = "stream";
        Stream stream = new StreamImpl(scope, streamName);
        
        bucketStore.addStreamToBucketStore(BucketStore.ServiceType.RetentionService, scope, streamName, executor).join();

        // verify that at least one of the buckets got the notification
        int bucketId = stream.getScopedName().hashCode() % 3;
        Set<String> streams = bucketStore.getStreamsForBucket(BucketStore.ServiceType.RetentionService, bucketId, executor).join();
        
        BucketService bucketService = bucketServices.get(bucketId);
        AtomicBoolean added = new AtomicBoolean(false);
        RetryHelper.loopWithDelay(() -> !added.get(), () -> CompletableFuture.completedFuture(null)
                .thenAccept(x -> added.set(bucketService.getKnownStreams().size() > 0)), Duration.ofSeconds(1).toMillis(), executor).join();
        assertTrue(bucketService.getKnownStreams().contains(stream));

        bucketStore.removeStreamFromBucketStore(BucketStore.ServiceType.RetentionService, scope, streamName, executor).join();
        AtomicBoolean removed = new AtomicBoolean(false);
        RetryHelper.loopWithDelay(() -> !removed.get(), () -> CompletableFuture.completedFuture(null)
                .thenAccept(x -> removed.set(bucketService.getKnownStreams().size() == 0)), Duration.ofSeconds(1).toMillis(), executor).join();
        assertEquals(0, bucketService.getKnownStreams().size());
    }
}
