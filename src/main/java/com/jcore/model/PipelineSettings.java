package com.jcore.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PipelineSettings {
    String accountNr;
    String region;
    String repositoryName;
    String clusterName;
    Map<String, Service> services;

    public record Service(String serviceName, String containerName) {
    }
}
