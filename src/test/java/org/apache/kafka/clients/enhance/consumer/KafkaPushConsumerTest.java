package org.apache.kafka.clients.enhance.consumer;

import org.apache.kafka.clients.enhance.ExtMessage;
import org.apache.kafka.clients.enhance.consumer.listener.ConcurrentConsumeHandlerContext;
import org.apache.kafka.clients.enhance.consumer.listener.ConcurrentMessageHandler;
import org.apache.kafka.clients.enhance.consumer.listener.ConsumeStatus;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
        props.put("group.id", "test_zzz_2");
        props.put("client.id", "my_4");
        props.put("enable.auto.commit", "true");
        props.put("auto.commit.interval.ms", "1000");

        consumer = new KafkaPushConsumer<String>(props, String.class);
        consumer.consumeSetting()
                .consumeBatchSize(10)
                .consumeModel(ConsumeModel.GROUP_CLUSTERING)
                .maxMessageDealTimeMs(10, TimeUnit.SECONDS);

        final AtomicInteger total = new AtomicInteger(0);
        final Map<String, Integer> calc = new HashMap<>();
        final Object lock = new Object();


        consumer.registerHandler(new ConcurrentMessageHandler<String>() {
            @Override
            public ConsumeStatus consumeMessage(List<ExtMessage<String>> message, ConcurrentConsumeHandlerContext consumeContext) throws InterruptedException {

                /*System.out.println("message num=" + message.size() + "\t --->" + message.get(0).getRetryCount());
                consumeContext.updateConsumeStatusInBatch(0, true);*/

                int i = 0;
                total.addAndGet(message.size());
                System.out.println("message count=" + message.size());
                for (ExtMessage<String> rec : message) {
                    System.out.println("message key=" + rec.getMsgKey() + " message time=" + new Date(rec.getStoreTimeMs()));
                    String key = rec.getMsgKey();
                    synchronized (lock) {
                        if (calc.containsKey(key)) {
                            calc.put(key, calc.get(key).intValue() + 1);
                        } else {
                            calc.put(key, 1);
                        }
                    }
                    consumeContext.updateConsumeStatusInBatch(i++, true);
                    if (rec.getMsgKey().equals("5"))
                        TimeUnit.MILLISECONDS.sleep(150000L);

                }

                return ConsumeStatus.CONSUME_SUCCESS;
            }
        });

        consumer.subscribe("test");
        consumer.start();
        boolean seekOk = false;
        while (true) {
            System.out.println("total====>\t" + total.get());

            TimeUnit.SECONDS.sleep(10);

            for (int i = 0; i < 10; i++) {
               /* if (i == 0) {
                    consumer.seekToEnd();
                } else if (i == 3 && !seekOk) {
                    consumer.seek(new TopicPartition("test", 0), 11318757);
                    seekOk = true;
                } else if (i == 5) {
                    //consumer.seekToTime("2018-02-08T12:00:00.000");
                }*/
                if (!calc.containsKey(String.valueOf(i))) {
                    System.out.println("lost ====> " + i);
                } else if (calc.get(String.valueOf(i)) > 1) {
                    System.out.println("replicated ====> " + i + " times=" + calc.get(String.valueOf(i)));
                }
            }
        }

    }

    @Test
    public void start() throws Exception {


        TimeUnit.SECONDS.sleep(1000);
    }

}