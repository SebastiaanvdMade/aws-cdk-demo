package com.jcore.model;

import lombok.Builder;
import lombok.Data;
import software.amazon.awscdk.services.secretsmanager.CfnSecret;

import java.util.List;

@Data
@Builder
public class ServiceSettings {
    String cluster;
    String containerName;
    String targetGroup;
    String securityGroup;
    List<String> subnets;
    String mode;
    int port;
    String snsTopic;
    String sqsQueue;
    String databaseUrl;
    CfnSecret connectionString;
    String username;
    CfnSecret password;
    String region;
}
