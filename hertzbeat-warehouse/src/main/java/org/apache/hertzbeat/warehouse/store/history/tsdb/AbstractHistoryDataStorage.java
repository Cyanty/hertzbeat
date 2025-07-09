/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.warehouse.store.history.tsdb;

import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.timer.HashedWheelTimer;
import org.apache.hertzbeat.common.timer.Timeout;
import org.apache.hertzbeat.common.timer.TimerTask;
import org.apache.hertzbeat.warehouse.config.WarehouseBatchInsertProperties;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * data storage abstract class
 */
@Slf4j
public abstract class AbstractHistoryDataStorage implements HistoryDataReader, HistoryDataWriter, DisposableBean {

    private static final long MAX_WAIT_MS = 500L;
    private static final int MAX_RETRIES = 3;

    private final String storageTypeName;
    private HashedWheelTimer metricsFlushTimer = null;
    private MetricsFlushTask metricsFlushtask = null;
    private final BlockingQueue<MetricsContent> metricsBufferQueue;

    protected WarehouseBatchInsertProperties warehouseBatchInsertProperties;

    protected boolean serverAvailable;

    public AbstractHistoryDataStorage(String storageTypeName, WarehouseBatchInsertProperties warehouseBatchInsertProperties) {
        this.storageTypeName = storageTypeName;
        this.warehouseBatchInsertProperties = warehouseBatchInsertProperties;
        metricsBufferQueue = new LinkedBlockingQueue<>(warehouseBatchInsertProperties.bufferSize());
        if (warehouseBatchInsertProperties.enabled()) {
            initializeFlushTimer();
        }
    }

    public abstract void doSaveData(List<? extends MetricsContent> batch);

    private void initializeFlushTimer() {
        metricsFlushTimer = new HashedWheelTimer(r -> {
            Thread thread = new Thread(r, storageTypeName + "-flush-timer");
            thread.setDaemon(true);
            return thread;
        }, 1, TimeUnit.SECONDS, 512);
        metricsFlushtask = new MetricsFlushTask();
        metricsFlushTimer.newTimeout(metricsFlushtask, 0, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (metricsFlushTimer != null && !metricsFlushTimer.isStop()) {
            metricsFlushTimer.stop();
        }
    }

    /**
     * @return data storage available
     */
    @Override
    public boolean isServerAvailable() {
        return serverAvailable;
    }


    /**
     * add victoriaMetricsContent to buffer
     * @param contentList victoriaMetricsContent List
     */
    public void sendMetrics(List<? extends MetricsContent> contentList) {
        if (!warehouseBatchInsertProperties.enabled()){
            doSaveData(contentList);
            return;
        }
        for (MetricsContent content : contentList) {
            boolean offered = false;
            int retryCount = 0;
            while (!offered && retryCount < MAX_RETRIES) {
                try {
                    // Attempt to add to the queue for a limited time
                    offered = metricsBufferQueue.offer(content, MAX_WAIT_MS, TimeUnit.MILLISECONDS);
                    if (!offered) {
                        // If the queue is still full, trigger an immediate refresh to free up space
                        if (retryCount == 0) {
                            log.debug("{} buffer queue is full, triggering immediate flush", storageTypeName);
                            triggerImmediateFlush();
                        }
                        retryCount++;
                        // The short sleep allows the queue to clear out
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(100L * retryCount);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("[{}] Interrupted while offering metrics to buffer queue", storageTypeName, e);
                    break;
                }
            }
            // When the maximum number of retries is reached, if it still cannot be added to the queue, the data is saved directly
            if (!offered) {
                log.warn("[{}] Failed to add metrics to buffer after {} retries, saving directly", storageTypeName, MAX_RETRIES);
                try {
                    doSaveData(contentList);
                } catch (Exception e) {
                    log.error("[{}] Failed to save metrics directly: {}", storageTypeName, e.getMessage(), e);
                }
            }
        }
        // Refresh in advance to avoid waiting
        if (metricsBufferQueue.size() >= warehouseBatchInsertProperties.bufferSize() * 0.8) {
            triggerImmediateFlush();
        }
    }

    private void triggerImmediateFlush() {
        metricsFlushTimer.newTimeout(metricsFlushtask, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Regularly refresh the buffer queue
     */
    private class MetricsFlushTask implements TimerTask {
        @Override
        public void run(Timeout timeout) {
            try {
                List<MetricsContent> batch = new ArrayList<>(warehouseBatchInsertProperties.bufferSize());
                metricsBufferQueue.drainTo(batch, warehouseBatchInsertProperties.bufferSize());
                if (!batch.isEmpty()) {
                    doSaveData(batch);
                    log.debug("[{}] Flushed {} metrics items", storageTypeName, batch.size());
                }
                if (metricsFlushTimer != null && !metricsFlushTimer.isStop()) {
                    metricsFlushTimer.newTimeout(this, warehouseBatchInsertProperties.flushInterval(), TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("[{}] flush task error: {}", storageTypeName, e.getMessage(), e);
            }
        }
    }

    public interface MetricsContent {
    }
}
