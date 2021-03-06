package org.apache.kafka.clients.enhance.consumer;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.enhance.*;
import org.apache.kafka.clients.enhance.exception.KafkaConsumeException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.kafka.clients.enhance.ExtMessageDef.MAX_DELAY_TIME_LEVEL;
import static org.apache.kafka.clients.enhance.ExtMessageDef.PROPERTY_DELAY_RESEND_TOPIC;

public abstract class AbstractConsumeService<K> implements ConsumeService<K> {
	protected static final Logger logger = LoggerFactory.getLogger(AbstractConsumeService.class);
	protected static final long SEND_MESSAGE_BACK_WAIT_TIMEOUT_MS = 3000L;

	private final ThreadPoolExecutor execTaskService;
	private final ArrayBlockingQueue<Runnable> taskQueue;
	private final ScheduledExecutorService scheduleExecTaskService;
	protected final ClientThreadFactory clientThreadFactory = new ClientThreadFactory("consume-service-thread-pool");
	protected final KafkaPollMessageService<K> pollService;
	protected ShutdownableThread dispatchService;

	protected final ReentrantLock syncLock = new ReentrantLock(true);

	protected final AbstractOffsetStorage offsetPersistor;
	protected final EnhanceConsumer<K> safeConsumer;
	protected final KafkaProducer<K, ExtMessage<K>> innerSender;
	protected final PartitionDataManager<K, ExtMessage<K>> partitionDataManager;

	protected final ConsumeClientContext<K> clientContext;
	protected volatile boolean isRunning = false;

	public AbstractConsumeService(final EnhanceConsumer<K> safeConsumer,
			final KafkaProducer<K, ExtMessage<K>> innerSender, final ConsumeClientContext<K> clientContext) {
		this.safeConsumer = safeConsumer;
		this.innerSender = innerSender;
		this.clientContext = clientContext;
		this.partitionDataManager = new PartitionDataManager<>();
		this.pollService = new KafkaPollMessageService("kafka-poll-message-service", safeConsumer, partitionDataManager,
				clientContext, syncLock);
		this.taskQueue = new ArrayBlockingQueue<>(clientContext.consumeQueueSize(), true);
		//[default resetStrategy] is RejectedStrategy, need process RejectedException.
		int coreThreadNum = clientContext.consumeThreadNum();
		this.execTaskService = new ThreadPoolExecutor(coreThreadNum, coreThreadNum << 1L, 1000 * 15,//
				TimeUnit.MILLISECONDS, this.taskQueue, clientThreadFactory);

		this.scheduleExecTaskService = Executors.newSingleThreadScheduledExecutor(clientThreadFactory);

		switch (clientContext.consumeModel()) {
			case GROUP_BROADCASTING:
				this.offsetPersistor = new OffsetFileStorage(safeConsumer, partitionDataManager, clientContext);

				break;
			case GROUP_CLUSTERING:
			case GROUP_NULL_MODEL:
			default:
				this.offsetPersistor = new OffsetBrokerStorage(safeConsumer, partitionDataManager, clientContext);
				break;
		}

	}

	@Override
	public void start() {
		logger.debug("[AbstractConsumeService] start service.");
		syncLock.lock();
		try {
			if (!isRunning) {
				subscribe(clientContext.getTopics());
				pollService.start();
				dispatchService.start();
				offsetPersistor.start();
				isRunning = true;
			}
		} catch (Exception ex) {
			shutdownNow();
			throw new KafkaConsumeException("start consumeService failed.");
		} finally {
			syncLock.unlock();
		}

	}

	@Override
	public void shutdown() {
		shutdown(DEFAULT_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	public void shutdownNow() {
		shutdown(0, TimeUnit.MILLISECONDS);
	}

	@Override
	public void shutdown(long timeout, TimeUnit unit) {
		logger.debug("[AbstractConsumeService] start closing service.");
		syncLock.lock();
		try {
			if (isRunning) {
				pollService.shutdown();
				dispatchService.shutdown();

				this.taskQueue.clear();
				if (0 >= timeout) {
					logger.debug("[AbstractConsumeService] is isRunning at once.");
					this.execTaskService.shutdownNow();
					this.scheduleExecTaskService.shutdownNow();
				} else {
					logger.debug("[AbstractConsumeService] awaitTermination for [{}] ms.", unit.toMillis(timeout));
					this.execTaskService.shutdown();
					this.scheduleExecTaskService.shutdown();
					try {
						this.execTaskService.awaitTermination(timeout, unit);
						this.scheduleExecTaskService.awaitTermination(timeout, unit);
					} catch (InterruptedException e) {
						logger.warn("[AbstractConsumeService] interrupted exception. due to ", e);
					}
				}

				offsetPersistor.shutdown();
				isRunning = false;
			}
		} catch (Throwable e) {
			logger.warn("[AbstractConsumeService] close error. due to ", e);
		} finally {
			syncLock.unlock();
		}
	}

	@Override
	public void updateCoreThreadNum(int coreThreadNum) {
		try {
			execTaskService.setCorePoolSize(coreThreadNum);
		} catch (IllegalArgumentException e) {
			logger.warn("[AbstractConsumeService] update consuming thread-pool coreThread error. due to ", e);
		}
	}

	@Override
	public int getThreadCores() {
		return execTaskService.getCorePoolSize();
	}

	@Override
	public int getQueueSize() {
		return this.taskQueue.size();
	}

	@Override
	public void submitConsumeRequest(AbstractConsumeTaskRequest<K> requestTask) {
		try {
			dispatchTaskAtOnce(requestTask);
		} catch (RejectedExecutionException e) {
			logger.warn(
					"[AbstractConsumeService-submitConsumeRequest] task is too much. and wait 3s and dispatch again.");
			dispatchTaskLater(requestTask, clientContext.clientTaskRetryBackoffMs(), TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public boolean sendMessageBack(String topic, ExtMessage<K> msg, int delayLevel) {
		if (null == topic || null == msg) {
			logger.warn("sendMessageBack error. due to topic[{}] is null or msg[{}] is null.", topic, msg);
			return false;
		}
		ProducerRecord<K, ExtMessage<K>> record = null;
		if (1 <= delayLevel && MAX_DELAY_TIME_LEVEL >= delayLevel) {
			String delayedTopic = getDelayedTopicName(delayLevel);
			msg.addProperty(PROPERTY_DELAY_RESEND_TOPIC, topic);
			ExtMessageUtils.setDelayedLevel(msg, delayLevel);
			record = new ProducerRecord<>(delayedTopic, msg.getMsgKey(), msg);
			//record = new ProducerRecord<>(clientContext.retryTopicName(), msg.getMsgKey(), msg);
			record.headers().add(PROPERTY_DELAY_RESEND_TOPIC, topic.getBytes(ExtMessageDef.STRING_ENCODE));
		} else {
			record = new ProducerRecord<>(topic, msg.getMsgKey(), msg);
		}

		try {
			Future<RecordMetadata> result = innerSender.send(record);
			result.get(SEND_MESSAGE_BACK_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			logger.trace("sendMessageBack message[{}] successfully.", record.toString());
			return true;
		} catch (Throwable e) {
			logger.warn("sendMessageBack failed. records = [{}]", record);
		}
		return false;

	}

	private String getDelayedTopicName(int delayLevel) {
		return DelayedMessageTopic.getDelayedTopicNameByLevel(delayLevel, null, null);
	}

	private Set<TopicPartition> filterAssignedRetryTopic(Set<TopicPartition> origAssigned) {
		Set<TopicPartition> filterTopicPartitions = new HashSet<>();
		for (TopicPartition tp : origAssigned) {
			if (!tp.topic().equals(clientContext.retryTopicName())) {
				filterTopicPartitions.add(tp);
			}
		}
		return filterTopicPartitions;
	}

	@Override
	public void seek(TopicPartition partition, long offset) {
		if (isRunning) {
			logger.info("TopicPartition[{}] seek to [{}].", partition, offset);
			syncLock.lock();
			try {
				safeConsumer.seek(partition, offset);
				partitionDataManager.resetPartitionData(partition);
				offsetPersistor.removeOffset(partition);
			} catch (Exception ex) {
				logger.warn("seek offset error. due to ", ex);
			} finally {
				syncLock.unlock();
			}
		} else {
			logger.info("Consume service hasn't been initialized.");
		}
	}

	@Override
	public void seekToTime(long timestamp) {
		if (isRunning) {
			syncLock.lock();
			try {
				Set<TopicPartition> tps = filterAssignedRetryTopic(safeConsumer.assignment());
				HashMap<TopicPartition, Long> searchByTimestamp = new HashMap<>();

				for (TopicPartition tp : tps) {
					searchByTimestamp.put(tp, timestamp);
				}

				for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : safeConsumer
						.offsetsForTimes(searchByTimestamp).entrySet()) {
					safeConsumer.seek(entry.getKey(), entry.getValue().offset());
				}
				partitionDataManager.resetAllPartitionData();
				offsetPersistor.clearOffset();

			} catch (Exception ex) {
				logger.warn("seekToTime error. due to ", ex);
			} finally {
				syncLock.unlock();
			}
		} else {
			logger.info("Consume service hasn't been initialized.");
		}
	}

	@Override
	public void seekToBeginning() {
		if (isRunning) {
			syncLock.lock();
			try {
				safeConsumer.seekToBeginning(filterAssignedRetryTopic(safeConsumer.assignment()));
				partitionDataManager.resetAllPartitionData();
				offsetPersistor.clearOffset();
			} catch (Exception ex) {
				logger.warn("seekToBeginning error. due to ", ex);
			} finally {
				syncLock.unlock();
			}
		} else {
			logger.info("Consume service  hasn't been initialized.");
		}
	}

	@Override
	public void seekToEnd() {
		if (isRunning) {
			syncLock.lock();
			try {
				safeConsumer.seekToEnd(filterAssignedRetryTopic(safeConsumer.assignment()));
				partitionDataManager.resetAllPartitionData();
				offsetPersistor.clearOffset();
			} catch (Exception ex) {
				logger.warn("seekToEnd error. due to ", ex);
			} finally {
				syncLock.unlock();
			}
		} else {
			logger.info("Consume service hasn't been initialized.");
		}
	}

	@Override
	public ConsumerRebalanceListener getRebalanceListener() {
		return offsetPersistor;
	}

	@Override
	public void subscribe(Collection<String> topics) {
		syncLock.lock();
		try {
			safeConsumer.subscribe(topics, offsetPersistor);
		} catch (Exception ex) {
			logger.warn("ConsumeService subscribe error. due to ", ex);
		} finally {
			syncLock.unlock();
		}
	}

	@Override
	public void unsubscribe() {
		syncLock.lock();
		try {
			safeConsumer.unsubscribe();
		} catch (Exception ex) {
			logger.warn("cancel topic subscribe error. due to ", ex);
		} finally {
			syncLock.unlock();
		}
	}

	@Override
	public void suspend() {
		pollService.stopPollingMessage();
	}

	@Override
	public void resume() {
		pollService.resumePollingMessage();
	}

	public void dispatchTaskLater(final AbstractConsumeTaskRequest<K> requestTask, final long timeout,
			final TimeUnit unit) {
		try {
			scheduleExecTaskService.schedule(new Runnable() {
				@Override
				public void run() {
					try {
						dispatchTaskAtOnce(requestTask);
					} catch (Exception ex1) {
						logger.warn(
								"dispatchTaskAtOnce() failed, because partition is full. invoke dispatchTaskLater().",
								ex1);
						dispatchTaskLater(requestTask, timeout, unit);
					}
				}
			}, timeout, unit);
		} catch (Throwable t) {
			logger.warn("dispatchTaskLater() failed.", t);
			Utility.sleep(unit.toMillis(timeout));
			dispatchTaskLater(requestTask, timeout, unit);
		}
	}

	//maybe throw RejectedExecutionException
	public Future<ConsumeTaskResponse> dispatchTaskAtOnce(AbstractConsumeTaskRequest<K> requestTask) {
		Future<ConsumeTaskResponse> responseFuture = null;
		if (null != requestTask) {
			requestTask.updateTimestamp();
			responseFuture = execTaskService.submit(requestTask);
			requestTask.setTaskResponseFuture(responseFuture);
		}
		return responseFuture;
	}

}
