package com.jcore;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnTag;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.CfnEIP;
import software.amazon.awscdk.services.ec2.CfnInternetGateway;
import software.amazon.awscdk.services.ec2.CfnNatGateway;
import software.amazon.awscdk.services.ec2.CfnRoute;
import software.amazon.awscdk.services.ec2.CfnRouteTable;
import software.amazon.awscdk.services.ec2.CfnSubnet;
import software.amazon.awscdk.services.ec2.CfnSubnetRouteTableAssociation;
import software.amazon.awscdk.services.ec2.CfnVPC;
import software.amazon.awscdk.services.ec2.CfnVPCGatewayAttachment;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.Subnet;
import software.constructs.Construct;

import java.util.List;

public class AwsCursusStack extends Stack {

    private static final String PREFIX = "sebastiaans-";

    private final AwsEc2Service ec2Service = new AwsEc2Service(this, PREFIX);
    private final AwsEcsService ecsService = new AwsEcsService(this, PREFIX);

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

        var vpc = createVpc("10.0.0.0/16");
        var publicSubnetOne = createSubnet("10.0.111.0/24", vpc.getAttrVpcId(), true, "uno", "a");
        var privateSubnetOne = createSubnet("10.0.221.0/24", vpc.getAttrVpcId(), false, "uno", "a");
        var privateSubnetTwo = createSubnet("10.0.222.0/24", vpc.getAttrVpcId(), false, "dos", "b");
        var privateSubnets = List.of(privateSubnetOne.getSubnetId(), privateSubnetTwo.getSubnetId());

        var gateway = createInternetGatewayAndAttachToVpc(vpc.getAttrVpcId());
        var natGateway = createNatGatewayAndAttachToSubnet(publicSubnetOne.getSubnetId());
        var rt1 = createRouteTable(vpc, publicSubnetOne, true, "A");
        var publicRouteA = createRoute(rt1.getAttrRouteTableId(), gateway.getAttrInternetGatewayId(), true, "A");
        var rtA = createRouteTable(vpc, privateSubnetOne, false, "A");
        var rtB = createRouteTable(vpc, privateSubnetTwo, false, "B");
        var privateRouteA = createRoute(rtA.getAttrRouteTableId(), natGateway.getAttrNatGatewayId(), false, "A");
        var privateRouteB = createRoute(rtB.getAttrRouteTableId(), natGateway.getAttrNatGatewayId(), false, "B");

        //var iamRole = ec2Service.getCnfRole();
        var securityGroup = ec2Service.createSecurityGroup(vpc.getAttrVpcId(), "default");
        var securityGroupBalancer = ec2Service.createSecurityGroup(vpc.getAttrVpcId(), "balancer");
        var balancer = ec2Service.createLoadBalancer(vpc.getAttrVpcId(),
                List.of(privateSubnetOne.getSubnetId(), privateSubnetTwo.getSubnetId()),
                securityGroupBalancer.getAttrGroupId());

        var nginxInstance = ec2Service.createNginxInstance(publicSubnetOne.getSubnetId(), "NGINX", securityGroup.getAttrGroupId());

        var cluster = ecsService.createCluster();

        //Messenger SEND
        var targetGroupSend = ecsService.createTargetGroup(vpc.getAttrVpcId(), "send", 80);
        var listenerSend = ecsService.createListener(balancer.getAttrLoadBalancerArn(), targetGroupSend.getAttrTargetGroupArn(), "send", 80);
        listenerSend.addDependency(targetGroupSend);
        var messengerServiceSend = ecsService.createService(cluster.getAttrArn(), targetGroupSend.getAttrTargetGroupArn(), securityGroup.getAttrId(), privateSubnets, "send", 80);
        messengerServiceSend.addDependency(listenerSend);

        //Messenger RECEIVE
        var targetGroupReceive = ecsService.createTargetGroup(vpc.getAttrVpcId(), "receive", 81);
        var listenerReceive = ecsService.createListener(balancer.getAttrLoadBalancerArn(), targetGroupReceive.getAttrTargetGroupArn(), "receive", 81);
        listenerReceive.addDependency(targetGroupReceive);
        var messengerServiceReceive = ecsService.createService(cluster.getAttrArn(), targetGroupReceive.getAttrTargetGroupArn(), securityGroup.getAttrId(), privateSubnets, "receive", 81);
        messengerServiceReceive.addDependency(listenerReceive);
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
        System.out.println("Created RouteTable: " + routeTable.getAttrRouteTableId());

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


}
