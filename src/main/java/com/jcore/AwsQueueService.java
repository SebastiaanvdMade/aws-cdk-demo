package com.jcore;

import software.amazon.awscdk.services.sns.CfnTopic;
import software.amazon.awscdk.services.sqs.CfnQueue;
import software.constructs.Construct;

import java.util.List;

public class AwsQueueService {

    private final Construct scope;
    private final String prefix;

    public AwsQueueService(Construct scope, String prefix) {
        this.scope = scope;
        this.prefix = prefix;
    }

    public CfnTopic createTopic(String queueEndpoint) {
        return CfnTopic.Builder
                .create(scope, prefix + "sns-topic")
                .topicName(prefix + "sns-topic")
                .subscription(List.of(CfnTopic.SubscriptionProperty.builder()
                        .endpoint(queueEndpoint)
                        .protocol("sqs")
                        .build()
                ))
                .build();
    }

    public CfnQueue createQueue(String queueName) {
        return CfnQueue.Builder
                .create(scope, prefix + "sqs-queue")
                .queueName(queueName)
                .build();
    }


}
