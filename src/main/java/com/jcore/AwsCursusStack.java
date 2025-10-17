package com.jcore;

import com.jcore.model.PipelineSettings;
import com.jcore.model.ServiceSettings;
import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnTag;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.docdb.CfnDBCluster;
import software.amazon.awscdk.services.ec2.CfnEIP;
import software.amazon.awscdk.services.ec2.CfnInternetGateway;
import software.amazon.awscdk.services.ec2.CfnNatGateway;
import software.amazon.awscdk.services.ec2.CfnRoute;
import software.amazon.awscdk.services.ec2.CfnRouteTable;
import software.amazon.awscdk.services.ec2.CfnSecurityGroup;
import software.amazon.awscdk.services.ec2.CfnSubnet;
import software.amazon.awscdk.services.ec2.CfnSubnetRouteTableAssociation;
import software.amazon.awscdk.services.ec2.CfnVPC;
import software.amazon.awscdk.services.ec2.CfnVPCGatewayAttachment;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.Subnet;
import software.amazon.awscdk.services.ecr.CfnRepository;
import software.amazon.awscdk.services.ecs.CfnCluster;
import software.amazon.awscdk.services.ecs.CfnService;
import software.amazon.awscdk.services.secretsmanager.CfnSecret;
import software.amazon.awscdk.services.sns.CfnTopic;
import software.amazon.awscdk.services.sqs.CfnQueue;
import software.constructs.Construct;

import java.util.Collections;
import java.util.List;

public class AwsCursusStack extends Stack {

    private static final String PREFIX = "sebastiaans-";
    private static final String USER = "sebastiaan";

    private final AwsEc2Service ec2Service = new AwsEc2Service(this, PREFIX);
    private final AwsEcsService ecsService = new AwsEcsService(this, PREFIX);
    private final AwsQueueService queueService = new AwsQueueService(this, PREFIX);
    private final AwsDatabaseService databaseService = new AwsDatabaseService(this, PREFIX);
    private final AwsPipelineService pipelineService = new AwsPipelineService(this, PREFIX);

    public AwsCursusStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsCursusStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        CfnOutput.Builder.create(this, "AccountUsed").
                description("").
                value("Account: " + this.getAccount()).
                build();
        CfnOutput.Builder.create(this, "RegionUsed").
                description("").
                value("Region: " + this.getRegion()).
                build();

        //Queue
        var queue = queueService.createQueue("sebas-CDK-message-queue");
        var topic = queueService.createTopic(queue.getAttrArn());

        var vpc = createVpc("10.0.0.0/16");
        var publicSubnetOne = createSubnet("10.0.111.0/24", vpc.getAttrVpcId(), true, "uno", "a");
        var publicSubnetTwo = createSubnet("10.0.112.0/24", vpc.getAttrVpcId(), true, "dos", "b");
        var privateSubnetOne = createSubnet("10.0.221.0/24", vpc.getAttrVpcId(), false, "uno", "a");
        var privateSubnetTwo = createSubnet("10.0.222.0/24", vpc.getAttrVpcId(), false, "dos", "b");
        var privateSubnets = List.of(privateSubnetOne.getSubnetId(), privateSubnetTwo.getSubnetId());

        var gateway = createInternetGatewayAndAttachToVpc(vpc.getAttrVpcId());
        var natGateway = createNatGatewayAndAttachToSubnet(publicSubnetOne.getSubnetId());
        var rt1 = createRouteTable(vpc, publicSubnetOne, true, "A");
        var rt2 = createRouteTable(vpc, publicSubnetTwo, true, "B");
        var publicRouteA = createRoute(rt1.getAttrRouteTableId(), gateway.getAttrInternetGatewayId(), true, "A");
        var publicRouteB = createRoute(rt2.getAttrRouteTableId(), gateway.getAttrInternetGatewayId(), true, "B");
        var rtA = createRouteTable(vpc, privateSubnetOne, false, "A");
        var rtB = createRouteTable(vpc, privateSubnetTwo, false, "B");
        var privateRouteA = createRoute(rtA.getAttrRouteTableId(), natGateway.getAttrNatGatewayId(), false, "A");
        var privateRouteB = createRoute(rtB.getAttrRouteTableId(), natGateway.getAttrNatGatewayId(), false, "B");

        //var iamRole = ec2Service.getCnfRole();
        var securityGroup = ec2Service.createSecurityGroup(vpc.getAttrVpcId(), "default");
        var securityGroupBalancer = ec2Service.createSecurityGroup(vpc.getAttrVpcId(), "balancer");
        var applicationBalancer = ec2Service.createLoadBalancer(List.of(privateSubnetOne.getSubnetId(), privateSubnetTwo.getSubnetId()),
                securityGroupBalancer.getAttrGroupId(),
                true);

        //var nginxInstance = ec2Service.createNginxInstance(publicSubnetOne.getSubnetId(), "NGINX", securityGroup.getAttrGroupId());
        var passwordSecret = databaseService.createDatabasePassword(USER);
        var database = databaseService.createDatabaseInstance(privateSubnets, securityGroup.getAttrId(), USER, passwordSecret);
        database.addDependency(passwordSecret);
        var connectionString = databaseService.createConnectionStringSecret(database, passwordSecret);

        var cluster = ecsService.createCluster();

        var targetGroupSend = ecsService.createTargetGroup(vpc.getAttrVpcId(), "send", 80, Collections.emptyList());
        var targetGroupReceive = ecsService.createTargetGroup(vpc.getAttrVpcId(), "receive", 80, Collections.emptyList());

        var listener = ecsService.createALBListener(applicationBalancer.getAttrLoadBalancerArn(), 80);
        listener.addDependency(targetGroupSend);
        listener.addDependency(targetGroupReceive);
        var listenerRuleSend = ecsService.createListenerRule(listener.getAttrListenerArn(), targetGroupSend.getAttrTargetGroupArn(), "send", 1);
        var listenerRuleReceive = ecsService.createListenerRule(listener.getAttrListenerArn(), targetGroupReceive.getAttrTargetGroupArn(), "receive", 2);
        var listenerRuleWaiting = ecsService.createLoading(listener.getAttrListenerArn(), 50);

        var networkLoadBalancer = ec2Service.createLoadBalancer(List.of(publicSubnetOne.getSubnetId(), publicSubnetTwo.getSubnetId()),
                securityGroupBalancer.getAttrGroupId(),
                false);
        var nlbTargetGroup = ecsService.createTargetGroup(vpc.getAttrVpcId(), "send", 80, List.of(applicationBalancer.getRef()));
        var nlbListener = ecsService.createNLBListener(networkLoadBalancer.getAttrLoadBalancerArn(), nlbTargetGroup.getAttrTargetGroupArn(), 80);
        //nlbListener.addDependency(nlbTargetGroup);

        var serviceSettings = getDefaultMessengerSettings(cluster, securityGroup, privateSubnets, topic, queue, database, connectionString, passwordSecret);
        //Messenger SEND
        serviceSettings.targetGroup(targetGroupSend.getAttrTargetGroupArn());
        serviceSettings.mode("send");
        serviceSettings.containerName("berichtenverstuurding");
        var messengerServiceSend = ecsService.createService(serviceSettings.build());
        messengerServiceSend.addDependency(listener);

        //Messenger RECEIVE
        serviceSettings.targetGroup(targetGroupReceive.getAttrTargetGroupArn());
        serviceSettings.mode("receive");
        serviceSettings.containerName("berichtontvangding");
        var messengerServiceReceive = ecsService.createService(serviceSettings.build());
        messengerServiceReceive.addDependency(listener);

        var repository = pipelineService.createRepository();

        PipelineSettings pipelineSettings = getPipelineSettings(repository, cluster, messengerServiceSend);
        var pipeline = pipelineService.createPipeline(pipelineSettings);
    }

    private CfnVPC createVpc(final String cidrBlock) {
        var vpc = CfnVPC.Builder.create(this, PREFIX + "vpc").cidrBlock(cidrBlock).build();
        Tags.of(vpc).add("Name", PREFIX + "vpc");
        CfnOutput.Builder.create(this, "VpcCreated").value("VpcId: " + vpc.getAttrVpcId()).build();
        return vpc;
    }

    private ISubnet createSubnet(final String cidrBlock, final String vpcId, boolean publicNetwork, String name, String zone) {
        String label = publicNetwork ? "public" : "private";
        String id = String.format("%s-%s-%s-%s", PREFIX, label, "subnet", name);
        var cfnSubnet =
                CfnSubnet.Builder.create(this, id + "-cfn").
                        availabilityZone(getRegion() + zone).
                        cidrBlock(cidrBlock).
                        mapPublicIpOnLaunch(publicNetwork).
                        vpcId(vpcId).
                        build();
        Tags.of(cfnSubnet).add("Name", id);
        var subnet =
                Subnet.fromSubnetId(this, id, cfnSubnet.getAttrSubnetId());
        CfnOutput.Builder.create(this, label + "-" + name + "SubnetCreated").
                value("SubnetId: " + subnet.getSubnetId()).build();
        return subnet;
    }

    private CfnInternetGateway createInternetGatewayAndAttachToVpc(final String vpcId) {
        var internetGateway =
                CfnInternetGateway.Builder.create(this, PREFIX + "igw").
                        tags(List.of(CfnTag.builder().key("Name").value(PREFIX + "igw").build())).
                        build();
        CfnOutput.Builder.create(this, "InternetGatewayCreated").
                value("InternetGatewayId: " + internetGateway.getAttrInternetGatewayId()).
                build();
        var vpcGatewayAttachment =
                CfnVPCGatewayAttachment.Builder.create(this, PREFIX + "vpc-gateway-attachment").
                        vpcId(vpcId).
                        internetGatewayId(internetGateway.getAttrInternetGatewayId()).
                        build();
        CfnOutput.Builder.create(this, "VpcGatewayAttachmentCreated").
                value(String.format("VpcId: %s, InternetGatewayId: %s", vpcId, vpcGatewayAttachment.getInternetGatewayId())).
                build();
        return internetGateway;
    }

    private CfnRouteTable createRouteTable(
            final CfnVPC vpc, final ISubnet subnet, final boolean publicNetwork, String name) {
        String label = publicNetwork ? "public" : "private";
        var routeTable =
                CfnRouteTable.Builder.create(this, PREFIX + label + "-route-table-" + name).
                        vpcId(vpc.getAttrVpcId()).
                        tags(List.of(CfnTag.builder().key("Name").value(label + "-route-table-" + name).build())).
                        build();

        CfnOutput.Builder.create(this, PREFIX + label + "-" + name + "RouteTableCreated").
                value("RouteTableId: " + routeTable.getAttrRouteTableId()).
                build();

        var subnetRouteTableAssociation =
                CfnSubnetRouteTableAssociation.Builder.create(this, PREFIX + label + "-" + name + "-subnet-route-table-association").
                        subnetId(subnet.getSubnetId()).
                        routeTableId(routeTable.getAttrRouteTableId()).
                        build();

        CfnOutput.Builder.create(this, PREFIX + label + "-" + name + "SubnetRouteTableAssociationCreated").
                value(
                        String.format(
                                "SubnetId: %s, RouteTableId: %s",
                                subnetRouteTableAssociation.getSubnetId(),
                                subnetRouteTableAssociation.getRouteTableId()
                        )
                ).build();

        return routeTable;
    }

    private CfnRoute createRoute(final String routeTableId, String gatewayId, final boolean publicNetwork, String name) {
        var label = publicNetwork ? "internet" : "nat";
        var builder = CfnRoute.Builder.create(this, PREFIX + label + "-" + name + "-gateway-route").
                routeTableId(routeTableId).
                destinationCidrBlock("0.0.0.0/0");
        if (publicNetwork) {
            builder.gatewayId(gatewayId);
        } else {
            builder.natGatewayId(gatewayId);
        }
        return builder.build();
    }

    private CfnNatGateway createNatGatewayAndAttachToSubnet(String subnetId) {
        CfnEIP ip = CfnEIP.Builder.create(this, PREFIX + "elasticIP")
                .build();

        var natGateway = CfnNatGateway.Builder.create(this, PREFIX + "NAT-gateway")
                .subnetId(subnetId)
                .allocationId(ip.getAttrAllocationId())
                .connectivityType("public")
                .build();

        CfnOutput.Builder.create(this, "NatGatewayCreated").value("NatGatewayID: " + natGateway.getAttrNatGatewayId()).build();
        return natGateway;
    }

    private ServiceSettings.ServiceSettingsBuilder getDefaultMessengerSettings(CfnCluster cluster, CfnSecurityGroup securityGroup, List<@NotNull String> privateSubnets, CfnTopic topic, CfnQueue queue, CfnDBCluster database, CfnSecret connectionString, CfnSecret passwordSecret) {
        return ServiceSettings.builder()
                .region(this.getRegion())
                .cluster(cluster.getAttrArn())
                .securityGroup(securityGroup.getAttrId())
                .subnets(privateSubnets)
                .port(80)
                .snsTopic(topic.getAttrTopicArn())
                .sqsQueue(queue.getQueueName())
                .databaseUrl(database.getAttrEndpoint())
                .connectionString(connectionString)
                .username(USER)
                .password(passwordSecret);
    }

    private PipelineSettings getPipelineSettings(CfnRepository repository, CfnCluster cluster, CfnService messengerServiceSend) {
        return PipelineSettings.builder()
                .accountNr(this.getAccount())
                .region(this.getRegion())
                .repositoryName(repository.getRepositoryName())
                .clusterName(cluster.getClusterName())
                .serviceName(messengerServiceSend.getServiceName())
                .containerNameSend("berichtenverstuurding")
                .containerNameReceive("berichtontvangding")
                .build();
    }
}
