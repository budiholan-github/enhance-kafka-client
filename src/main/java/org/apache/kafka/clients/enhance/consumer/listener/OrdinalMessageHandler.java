package org.apache.kafka.clients.enhance.consumer.listener;

import org.apache.kafka.clients.enhance.ExtMessage;

import java.util.List;

/**
 * OrdinalMessageHandler
 */
public interface OrdinalMessageHandler<K> extends MessageHandler<K, OrdinalConsumeContext> {
	@Override
	ConsumeStatus consumeMessage(List<ExtMessage<K>> message, OrdinalConsumeContext consumeContext)
			throws InterruptedException;
}
