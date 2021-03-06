package org.apache.kafka.clients.enhance.consumer;

import org.apache.kafka.clients.enhance.ExtMessage;
import org.apache.kafka.clients.enhance.ExtMessageUtils;
import org.apache.kafka.clients.enhance.consumer.listener.ConcurrentConsumeContext;
import org.apache.kafka.clients.enhance.consumer.listener.ConcurrentMessageHandler;
import org.apache.kafka.clients.enhance.consumer.listener.ConsumeStatus;
import org.apache.kafka.common.TopicPartition;

import javax.net.ssl.SSLEngineResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.kafka.clients.enhance.ExtMessageDef.*;

public class ConcurrentConsumeTaskRequest<K> extends AbstractConsumeTaskRequest<K> {
	private static final AtomicLong requestIdGenerator = new AtomicLong(0L);
	private final long requestId = requestIdGenerator.incrementAndGet();
	private final ConcurrentConsumeContext handlerContext;
	private final ConcurrentMessageHandler<K> handler;
	private final String retryTopic;
	private final String deadletterTopic;

	public ConcurrentConsumeTaskRequest(AbstractConsumeService<K> service, PartitionDataManager manager,
			List<ExtMessage<K>> extMessages, TopicPartition topicPartition, ConsumeClientContext<K> clientContext) {
		super(service, manager, extMessages, topicPartition, clientContext);
		long firstOffsetInBatch = messages.get(FIRST_MESSAGE_IDX).getOffset();
		this.handlerContext = new ConcurrentConsumeContext(topicPartition, firstOffsetInBatch,
				clientContext.consumeBatchSize());
		this.handler = (ConcurrentMessageHandler<K>) clientContext.messageHandler();
		this.retryTopic = clientContext.retryTopicName();
		this.deadletterTopic = clientContext.deadLetterTopicName();
	}

	public long getRequestId() {
		return requestId;
	}

	private ConcurrentConsumeService<K> concurrentConsumeService() {
		return (ConcurrentConsumeService<K>) this.consumeService;
	}

	@Override
	public void processConsumeStatus(ConsumeStatus status) {
		List<Long> offsets = new ArrayList<>(messages.size());
		switch (status) {
			case CONSUME_RETRY_LATER:
				List<ExtMessage<K>> localRetryRecords = new ArrayList<>();
				int messageSize = messages.size();

				for (int idx = 0; idx < messageSize; idx++) {
					if (!handlerContext.getStatusByBatchIndex(idx)) {
						ExtMessage<K> msg = messages.get(idx);
						int delayLevel = msg.getRetryCount() + 1;
						if (handlerContext.isValidDelayLevel()) {
							delayLevel = handlerContext.getDelayLevelAtReconsume();
						}

						if (msg.getRetryCount() < MAX_RECONSUME_COUNT) {
							updateMessageAttrBeforeSendback(msg, delayLevel);

							boolean sendbackOk = false;
							switch (clientContext.consumeModel()) {
								case GROUP_CLUSTERING:
									sendbackOk = consumeService.sendMessageBack(retryTopic, msg, msg.getDelayedLevel());
									break;
								case GROUP_BROADCASTING:
								default:
									break;
							}

							if (!sendbackOk) {
								localRetryRecords.add(msg);
							}
						} else {
							switch (clientContext.consumeModel()) {
								case GROUP_CLUSTERING:
									((ConcurrentConsumeService) consumeService).createDeadLetterTopic();
									if (!consumeService.sendMessageBack(deadletterTopic, msg, 0)) {
										logger.warn(
												"sending dead letter message failed. please check message [{}], since it have retied many times.",
												msg);
									}
									break;
								case GROUP_BROADCASTING:
								default:
									logger.warn(
											"[ConcurrentConsumeTaskRequest-Broadcast] message [{}] will be dropped, since exceed max retry count.",
											msg);
									break;
							}

						}
					}
				}

				if (!localRetryRecords.isEmpty()) {
					logger.trace("need local retry message list:" + Arrays
							.toString(localRetryRecords.toArray(new Object[0])));
					consumeService.dispatchTaskLater(
							new ConcurrentConsumeTaskRequest<>(consumeService, manager, localRetryRecords,
									topicPartition, clientContext),
							DelayedMessageTopic.SYS_DELAYED_TOPIC_5S.getDurationMs(), TimeUnit.MILLISECONDS);
				}

				for (ExtMessage<K> message : messages) {
					if (localRetryRecords.isEmpty()) {
						offsets.add(message.getOffset());
					} else if (!localRetryRecords.contains(message)) {
						offsets.add(message.getOffset());
					}
				}
				logger.trace("start commitoffsets ---------------> " + offsets);
				manager.commitOffsets(topicPartition, offsets);
				break;
			case CONSUME_SUCCESS:
				for (ExtMessage<K> message : messages) {
					offsets.add(message.getOffset());
				}
				logger.trace("start commitoffsets ---------------> " + offsets);
				manager.commitOffsets(topicPartition, offsets);
				break;
			default:
				logger.warn("unknown ConsumeStatus. offset = " + offsets);
				manager.commitOffsets(topicPartition, offsets);
				break;
		}
		concurrentConsumeService().removeCompletedTask(requestId);
	}

	@Override
	public ConsumeTaskResponse call() throws Exception {
		ConsumeStatus status = ConsumeStatus.CONSUME_RETRY_LATER;
		try {
			if (topicPartition.topic().equals(retryTopic)) {
				List<ExtMessage<K>> newMessages = retrieveMessagesFromRetryTopic(this.messages);
				status = handler.consumeMessage(newMessages, this.handlerContext);
			} else {
				status = handler.consumeMessage(this.messages, this.handlerContext);
			}
			if (null == status) {
				logger.warn("consuming handler return null status, status will be replaced by [CONSUME_SUCCESS].");
				status = ConsumeStatus.CONSUME_SUCCESS;
			}
			return ConsumeTaskResponse.TASK_EXEC_SUCCESS;
		} catch (Throwable t) {
			if (t instanceof InterruptedException) {
				logger.info("[ConcurrentConsumeTaskRequest] callback exec too long(>{}ms), interrupted the task.",
						clientContext.maxMessageDealTimeMs());
			} else {
				logger.warn("[ConcurrentConsumeTaskRequest] callback execute failed. due to ", t);
			}
			return ConsumeTaskResponse.TASK_EXEC_FAILURE;
		} finally {
			try {
				processConsumeStatus(status);
			} catch (Exception e) {
				logger.warn("processConsumeStatus exception, due to:", e);
				this.updateTimestamp();
				processConsumeStatus(status);
			}
		}
	}

	private void updateMessageAttrBeforeSendback(ExtMessage<K> msg, int delayedLevel) {
		if (msg.getRetryCount() == 0) {
			msg.addProperty(PROPERTY_REAL_TOPIC, msg.getTopic());
			msg.addProperty(PROPERTY_REAL_PARTITION_ID, String.valueOf(msg.getPartion()));
			msg.addProperty(PROPERTY_REAL_OFFSET, String.valueOf(msg.getOffset()));
			msg.addProperty(PROPERTY_REAL_STORE_TIME, String.valueOf(msg.getStoreTimeMs()));
		}
		//retry count + 1
		ExtMessageUtils.updateRetryCount(msg);
		ExtMessageUtils.setDelayedLevel(msg, delayedLevel);
	}

	private List<ExtMessage<K>> retrieveMessagesFromRetryTopic(List<ExtMessage<K>> messagesFromRetryPartition) {
		List<ExtMessage<K>> newMessages = new ArrayList<>(messagesFromRetryPartition.size());
		for (ExtMessage<K> message : messagesFromRetryPartition) {
			ExtMessage<K> newMessage = ExtMessage.parseFromRetryMessage(message);
			newMessages.add(newMessage);
		}
		return Collections.unmodifiableList(newMessages);
	}
}
