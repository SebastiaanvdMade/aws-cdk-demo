package com.jcore.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ServiceSettings {
    String cluster;
    String targetGroup;
    String securityGroup;
    List<String> subnets;
    String mode;
    int port;
    String snsTopic;
    String sqsQueue;
    String databaseUrl;
}
