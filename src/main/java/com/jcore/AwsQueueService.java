package com.jcore;

import software.amazon.awscdk.services.sns.CfnTopic;
import software.amazon.awscdk.services.sqs.CfnQueue;
import software.amazon.awscdk.services.sqs.CfnQueuePolicy;
import software.constructs.Construct;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        var queue = CfnQueue.Builder
                .create(scope, prefix + "sqs-queue")
                .queueName(queueName)
                .build();

        var policy = CfnQueuePolicy.Builder
                .create(scope, prefix + "sqs-queue-access-policy")
                .queues(List.of(queue.getAttrQueueUrl()))
                .policyDocument(createPolicyDocument(queue.getAttrArn()))
                .build();
        return queue;
    }

    private Map<String, Object> createPolicyDocument(String queueArn) {
        var principal = new HashMap<String, Object>();
        principal.put("Service", "sns.amazonaws.com");

        var condition = new HashMap<String, Object>();
        condition.put("ArnLike", Map.of("aws:SourceArn", "arn:aws:sns:*:*:*"));

        var statement = new HashMap<String, Object>();
        statement.put("Effect", "Allow");
        statement.put("Principal", principal);
        statement.put("Action", "sqs:SendMessage");
        statement.put("Resource", queueArn);
        statement.put("Condition", condition);

        var policyDocument = new HashMap<String, Object>();
        policyDocument.put("Version", "2012-10-17");
        policyDocument.put("Statement", Collections.singletonList(statement));

        return policyDocument;
    }


}
