package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRunRegistryEvictionTest {

    @Test
    void evictionUsesOneSharedSchedulerThread() {
        AtomicInteger createdThreads = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "test-chat-run-evictor");
            thread.setDaemon(true);
            createdThreads.incrementAndGet();
            return thread;
        });
        try {
            ChatRunRegistry registry = new ChatRunRegistry(executor);
            registry.replayCapacity = 16;
            registry.ttlSeconds = 60;

            for (long id = 1L; id <= 8L; id++) {
                registry.startOrGet(id);
                registry.markDone(id);
            }

            assertTrue(createdThreads.get() <= 1, "eviction should not create one thread per run");
        } finally {
            executor.shutdownNow();
        }
    }
}
