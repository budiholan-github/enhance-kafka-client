package org.apache.kafka.clients.enhance.consumer;

import org.apache.kafka.clients.enhance.ExtMessage;
import org.apache.kafka.clients.enhance.consumer.listener.*;
import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class KafkaPushConsumerTest {
	private KafkaPushConsumer<String> consumer;

	@Before
	public void setUp() throws Exception {
		Properties props = new Properties();
		props.put("bootstrap.servers", "10.198.195.144:9092");
		props.put("acks", "all");
		props.put("retries", 1);
		props.put("batch.size", 16384);
		props.put("linger.ms", 1);
		props.put("group.id", "zyh_zzz_1");
		props.put("client.id", "zyh_my_1");
		props.put("enable.auto.commit", "true");
		props.put("auto.commit.interval.ms", "1000");
		props.put("isolation.level", "read_committed");

		consumer = new KafkaPushConsumer<>(props, String.class);
		consumer.consumeSetting().consumeBatchSize(10).consumeModel(ConsumeGroupModel.GROUP_CLUSTERING)
				.maxMessageDealTimeMs(12000, TimeUnit.SECONDS);

		final AtomicInteger total = new AtomicInteger(0);
		final Map<String, Integer> calc = new HashMap<>();
		final Object lock = new Object();
		/*consumer.addConsumeHook(new ConsumeMessageHook<String>() {
			@Override
            public ConsumerRecords<String, ExtMessage<String>> onConsume(ConsumerRecords<String, ExtMessage<String>> records) {
                System.out.println("hook1 onConsume ===========>" + records.count());
                return records;
            }

            @Override
            public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
                System.out.println("hook1 onCommit ===========>" + offsets);
            }

            @Override
            public void close() {

            }

            @Override
            public void configure(Map<String, ?> configs) {

            }
        });*/
		final AtomicInteger totoal = new AtomicInteger(0);

	/*	consumer.registerHandler(new OrdinalMessageHandler<String>() {
			@Override
			public ConsumeStatus consumeMessage(List<ExtMessage<String>> message, OrdinalConsumeContext consumeContext)
					throws InterruptedException {
				System.out.println("ThreadId=" + Thread.currentThread().getId() + " message count=" + message.size()
						+ " message retry time:" + (new Date()));
				consumeContext.suspendTimeInMs(1000);
				for (ExtMessage<String> msg : message) {
					System.out.println("---->" + new String(msg.getMsgValue()));
				}
				if (total.getAndIncrement() < 2) {
					TimeUnit.SECONDS.sleep(20);
				}
				System.out.println("===================> successfully, total = " + total.get());
				return ConsumeStatus.CONSUME_SUCCESS;
			}
		});*/
		consumer.registerHandler(new ConcurrentMessageHandler<String>() {
			@Override
			public ConsumeStatus consumeMessage(List<ExtMessage<String>> message, ConcurrentConsumeContext consumeContext)
					throws InterruptedException {
				System.out.println("ThreadId=" + Thread.currentThread().getId() + " message count=" + message.size()
						+ " message retry time:" + (new Date()));

				for (ExtMessage<String> msg : message) {
					System.out.println("---->" + new String(msg.getMsgValue()) + " length = " + msg.getMsgValue().length);
				}
				/*if (total.getAndIncrement() < 2) {
					TimeUnit.SECONDS.sleep(20);
				}*/
				consumeContext.setDelayLevelAtReconsume(1);
				System.out.println("===================> successfully, total = " + total.get());
				return ConsumeStatus.CONSUME_RETRY_LATER;
			}
		});


        /*consumer.registerHandler(new ConcurrentMessageHandler<String>() {
			@Override
            public ConsumeStatus consumeMessage(List<ExtMessage<String>> message, ConcurrentConsumeContext consumeContext) throws InterruptedException {

                *//*System.out.println("message num=" + message.size() + "\t --->" + message.get(0).getRetryCount());
                consumeContext.updateConsumeStatusInBatch(0, true);*//*

                int i = 0;
                total.addAndGet(message.size());
                System.out.println("message count=" + message.size() + " message retry time:" + (new Date()));
                for (ExtMessage<String> rec : message) {
                    System.out.println("message key=" + rec.getMsgKey() + " message time=" + new Date(rec.getStoreTimeMs())
                    + " message retrytime=" + rec.getRetryCount());
                    String key = rec.getMsgKey();
                    synchronized (lock) {
                        if (calc.containsKey(key)) {
                            calc.put(key, calc.get(key).intValue() + 1);
                        } else {
                            calc.put(key, 1);
                        }
                    }
                    //consumeContext.updateConsumeStatusInBatch(i++, true);
                    *//*if (rec.getMsgKey().equals("5"))
                        TimeUnit.MILLISECONDS.sleep(150000L);*//*

                }

                return ConsumeStatus.CONSUME_RETRY_LATER;
            }
        });
*/
		consumer.subscribe("test2", "^[^9].*");

     /*   consumer.consumeSetting().messageFilter(new AbstractExtMessageFilter() {
            @Override
            public boolean canDeliveryMessage(ExtMessage message, Headers headers) {
                return (new String(message.getMsgValue())).startsWith("2");
            }
        });*/
		consumer.start();
		boolean seekOk = false;
		TimeUnit.SECONDS.sleep(50000);
		//consumer.suspend();
		/*while (true) {
			//System.out.println("total====>\t" + total.get());
         *//*   if (!seekOk) {
                consumer.addConsumeHook(new ConsumeMessageHook<String>() {
                    @Override
                    public ConsumerRecords<String, ExtMessage<String>> onConsume(ConsumerRecords<String, ExtMessage<String>> records) {
                        System.out.println("hook2 onConsume ===========>" + records.count());
                        return records;
                    }

                    @Override
                    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
                        System.out.println("hook2 onCommit ===========>" + offsets);
                    }

                    @Override
                    public void close() {

                    }

                    @Override
                    public void configure(Map<String, ?> configs) {

                    }
                });
                seekOk = true;
            }*//*
			//TimeUnit.SECONDS.sleep(120);
			//consumer.resume();
			for (int i = 0; i < 10; i++) {
               *//* if (i == 0) {
                    consumer.seekToEnd();
                } else if (i == 3 && !seekOk) {
                    consumer.seek(new TopicPartition("test", 0), 11318757);
                    seekOk = true;
                } else if (i == 5) {
                    //consumer.seekToTime("2018-02-08T12:00:00.000");
                }*//*
				if (!calc.containsKey(String.valueOf(i))) {
					//System.out.println("lost ====> " + i);
				} else if (calc.get(String.valueOf(i)) > 1) {
					//System.out.println("replicated ====> " + i + " times=" + calc.get(String.valueOf(i)));
				}
			}
		}*/

	}

	@Test
	public void start() throws Exception {


		TimeUnit.SECONDS.sleep(1000000);
	}

}