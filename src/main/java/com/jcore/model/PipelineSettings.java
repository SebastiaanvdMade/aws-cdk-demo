package com.jcore.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PipelineSettings {
    String accountNr;
    String region;
    String repositoryName;
    String clusterName;
    String serviceName;
    String containerNameSend;
    String containerNameReceive;
}
