package org.apache.kafka.clients.enhance.consumer.listener;

import org.apache.kafka.clients.enhance.ExtMessageDef;
import org.apache.kafka.common.TopicPartition;

public class ConcurrentConsumeHandlerContext extends AbstractConsumeHandlerContext {

    private int delayLevelAtReconsume = Integer.MIN_VALUE;

    public ConcurrentConsumeHandlerContext(TopicPartition tp, long ackOffset, int batchSize) {
        super(tp, ackOffset, batchSize);
    }

    public boolean isValidDelayLevel() {
        return delayLevelAtReconsume > 0 && delayLevelAtReconsume < ExtMessageDef.MAX_RECONSUME_COUNT;
    }

    public int getDelayLevelAtReconsume() {
        return delayLevelAtReconsume;
    }

    public void setDelayLevelAtReconsume(int delayLevelAtReconsume) {
        this.delayLevelAtReconsume = delayLevelAtReconsume;
    }
}
