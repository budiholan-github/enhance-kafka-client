package org.apache.kafka.clients.enhance.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.enhance.ExtMessage;
import org.apache.kafka.clients.enhance.AbsExtMessageFilter;
import org.apache.kafka.clients.enhance.ShutdownableThread;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by steven03.zhang on 2017/12/13.
 */
class KafkaPollMessageService<K> extends ShutdownableThread {
    private final static Logger logger = LoggerFactory.getLogger(KafkaPollMessageService.class);

    private final ConsumerWithAdmin<K> safeConsumer;
    private final PartitionDataManager<K, ExtMessage<K>> partitionDataManager;
    private final ConsumeClientContext<K> clientContext;
    private final ReentrantLock consumeServiceLock;

    private volatile boolean isRunning;

    public KafkaPollMessageService(String serviceName, ConsumerWithAdmin<K> safeConsumer,
                                   PartitionDataManager<K, ExtMessage<K>> partitionDataManager,
                                   ConsumeClientContext<K> clientContext, ReentrantLock consumeServiceLock) {
        super(serviceName);
        this.safeConsumer = safeConsumer;
        this.clientContext = clientContext;
        this.partitionDataManager = partitionDataManager;
        this.consumeServiceLock = consumeServiceLock;
    }


    @Override
    public void doWork() {
        while (isRunning) {
            try {
                if (consumeServiceLock.tryLock(clientContext.pollMessageAwaitTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    try {
                        ConsumerRecords<K, ExtMessage<K>> records = safeConsumer.poll(clientContext.pollMessageAwaitTimeoutMs());
                        if (!records.isEmpty()) {
                            //filter messages
                            ConsumerRecords<K, ExtMessage<K>> filterMessages = filterMessage(records, clientContext.messageFilter());

                            Set<TopicPartition> needPausedPartitions = partitionDataManager.saveConsumerRecords(filterMessages);
                            Set<TopicPartition> hasPausedPartitions = safeConsumer.paused();

                            if (!hasPausedPartitions.isEmpty()) {
                                Set<TopicPartition> needResumePartitions = new HashSet<>();
                                for (TopicPartition tp : hasPausedPartitions) {
                                    if (!needPausedPartitions.contains(tp)) {
                                        needResumePartitions.add(tp);
                                    }
                                }
                                if (!needResumePartitions.isEmpty()) {
                                    safeConsumer.resume(needResumePartitions);
                                }
                            }
                            safeConsumer.pause(needPausedPartitions);

                        }
                    } catch (Throwable t) {
                        logger.warn("KafkaPollMessageService error. due to ", t);
                    } finally {
                        consumeServiceLock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("KafkaPollMessageService is interrupted.");
            }
        }
    }

    private ConsumerRecords<K, ExtMessage<K>> filterMessage(ConsumerRecords<K, ExtMessage<K>> records,
                                                            AbsExtMessageFilter<K> filter) {
        if (filter.isPermitAll()) return records;

        Map<TopicPartition, List<ConsumerRecord<K, ExtMessage<K>>>> filterRecords = new HashMap<>(records.count());
        Set<TopicPartition> tps = records.partitions();
        for (TopicPartition tp : tps) {
            ArrayList<ConsumerRecord<K, ExtMessage<K>>> fitlerRecordsByPartition = new ArrayList<>();
            List<ConsumerRecord<K, ExtMessage<K>>> partitionRecords = records.records(tp);

            for (ConsumerRecord<K, ExtMessage<K>> partitionRecord : partitionRecords) {
                if (filter.canDelieveryMessage(partitionRecord.value(), partitionRecord.headers())) {
                    fitlerRecordsByPartition.add(partitionRecord);
                }
            }
            filterRecords.put(tp, fitlerRecordsByPartition);
        }

        return new ConsumerRecords<>(filterRecords);
    }

    @Override
    public void start() {
        if (!isRunning) {
            synchronized (this) {
                if (!isRunning) {
                    isRunning = true;
                    try {
                        super.start();
                    } catch (Exception e) {
                        logger.warn("start [KafkaPollMessageService] service error. due to ", e);
                        shutdown();
                        throw new KafkaException("start [KafkaPollMessageService] failed.");
                    }
                }
            }
        } else {
            logger.info("[KafkaPollMessageService] has been started.");
        }
    }

    @Override
    public void shutdown() {
        if (isRunning) {
            synchronized (this) {
                if (isRunning) {
                    isRunning = false;
                    if (isAlive()) {
                        super.shutdown();
                    }
                }
            }
        } else {
            logger.info("[KafkaPollMessageService] has been shutdown.");
        }
    }

}
