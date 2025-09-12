package com.jcore;

import software.amazon.awscdk.services.docdb.CfnDBCluster;
import software.amazon.awscdk.services.docdb.CfnDBClusterParameterGroup;
import software.amazon.awscdk.services.docdb.CfnDBInstance;
import software.amazon.awscdk.services.docdb.CfnDBSubnetGroup;
import software.amazon.awscdk.services.secretsmanager.CfnSecret;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class AwsDatabaseService {

    private final Construct scope;
    private final String prefix;

    public AwsDatabaseService(Construct scope, String prefix) {
        this.scope = scope;
        this.prefix = prefix;
    }

    public CfnDBCluster createDatabaseInstance(List<String> subnets, String securityGroup, String username, CfnSecret password) {
        // 1. Create Cluster Parameter Group
        CfnDBClusterParameterGroup parameterGroup = CfnDBClusterParameterGroup.Builder.create(scope, prefix + "ParameterGroup")
                .description("Parameter group for DocumentDB cluster")
                .family("docdb5.0")
                .parameters(Map.of(
                        "tls", "disabled",
                        "ttl_monitor", "disabled"
                ))
                .build();

        CfnDBSubnetGroup subnetGroup = CfnDBSubnetGroup.Builder.create(scope, prefix + "db-subnets")
                .dbSubnetGroupDescription("Subnet group for DocumentDB")
                .subnetIds(subnets)
                .dbSubnetGroupName(prefix + "subnet-group")
                .build();

        // 2. Create DocumentDB Cluster
        CfnDBCluster cluster = CfnDBCluster.Builder.create(scope, prefix + "database-cluster")
                .dbClusterIdentifier(prefix + "data-cluster")
                .masterUsername(username)
                .masterUserPassword(password.getSecretString())
                .vpcSecurityGroupIds(List.of(securityGroup))
                .dbClusterParameterGroupName(parameterGroup.getRef())
                .dbSubnetGroupName(subnetGroup.getDbSubnetGroupName())
                .storageEncrypted(true)
                .backupRetentionPeriod(1)
                .build();

        // 3. Create DocumentDB Instance (1 node)
        CfnDBInstance instance = CfnDBInstance.Builder.create(scope, prefix + "database-instance")
                .dbInstanceIdentifier(prefix + "dbinstance")
                .dbInstanceClass("db.t3.medium")
                .dbClusterIdentifier(cluster.getRef())
                .build();

        return cluster;
    }

    public CfnSecret createDatabasePassword(String username) {
        return CfnSecret.Builder.create(scope, prefix + "doc-db-secret-password")
                .name(prefix + "database-wachtwoord")
                .generateSecretString(CfnSecret.GenerateSecretStringProperty.builder()
                        .secretStringTemplate("{\"username\":\"%s\"}".formatted(username))
                        .generateStringKey("password")
                        .excludeCharacters("\"@/\\ '")
                        .passwordLength(16)
                        .build())
                .build();
    }

    public CfnSecret createConnectionStringSecret(CfnDBCluster cluster) {
        String connectionString = "mongodb://%s:%s/?ssl=true&replicaSet=rs0".formatted(
                cluster.getAttrEndpoint(),
                cluster.getAttrPort()
        );

        return CfnSecret.Builder.create(scope, prefix + "secret-connection-string")
                .name(prefix + "database-connection-string")
                .secretString(connectionString)
                .build();
    }

}
