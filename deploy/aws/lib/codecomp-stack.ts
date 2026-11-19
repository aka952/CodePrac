import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';

export interface CodecompStackProps extends cdk.StackProps {
  /** owner/name of the GitHub repository allowed to deploy, e.g. aka952/CodePrac */
  githubRepo: string;
  instanceType: string;
  /** ARN of an existing GitHub OIDC provider in this account; one is created when absent */
  githubOidcProviderArn?: string;
}

/**
 * One EC2 instance that runs the Docker Compose stack (same as the OCI deployment), reachable only on 80/443.
 * No SSH: shell access and deploys go through SSM. GitHub Actions deploys through an OIDC role that may only
 * send commands to this instance.
 */
export class CodecompStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: CodecompStackProps) {
    super(scope, id, props);

    // Public subnet only, so no NAT gateway to pay for. The instance is protected by its security group.
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 1,
      natGateways: 0,
      subnetConfiguration: [{ name: 'public', subnetType: ec2.SubnetType.PUBLIC }],
    });

    const sg = new ec2.SecurityGroup(this, 'WebSg', { vpc, description: 'codecomp: HTTP and HTTPS only' });
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS');

    const role = new iam.Role(this, 'InstanceRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')],
    });
    // deploy.sh builds .env from these SecureString parameters (the default aws/ssm key needs no extra KMS grant)
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParametersByPath'],
      resources: [this.formatArn({ service: 'ssm', resource: 'parameter', resourceName: 'codecomp' })],
    }));

    const userData = ec2.UserData.forLinux();
    userData.addCommands(
      'set -euo pipefail',
      'snap install aws-cli --classic',
      `git clone https://github.com/${props.githubRepo}.git /opt/codecomp`,
      'chown -R ubuntu:ubuntu /opt/codecomp',
      // installs Docker and gVisor and pulls the sandbox images; written for OCI but plain Ubuntu, so it works here
      'SUDO_USER=ubuntu bash /opt/codecomp/deploy/oci/setup-server.sh',
    );

    const instance = new ec2.Instance(this, 'Server', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      securityGroup: sg,
      role,
      instanceType: new ec2.InstanceType(props.instanceType),
      machineImage: ec2.MachineImage.fromSsmParameter(
        '/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id'),
      blockDevices: [{
        deviceName: '/dev/sda1',
        volume: ec2.BlockDeviceVolume.ebs(30, { volumeType: ec2.EbsDeviceVolumeType.GP3, encrypted: true }),
      }],
      requireImdsv2: true,
      userData,
    });
    cdk.Tags.of(instance).add('app', 'codecomp');

    const eip = new ec2.CfnEIP(this, 'Ip', { instanceId: instance.instanceId });

    // GitHub Actions: may only run commands on this instance, and only from the main branch of the repository.
    const provider = props.githubOidcProviderArn
      ? iam.OidcProviderNative.fromOidcProviderArn(this, 'GithubOidc', props.githubOidcProviderArn)
      : new iam.OidcProviderNative(this, 'GithubOidc', {
          url: 'https://token.actions.githubusercontent.com',
          clientIds: ['sts.amazonaws.com'],
        });
    const deployRole = new iam.Role(this, 'GithubDeployRole', {
      assumedBy: new iam.WebIdentityPrincipal(provider.oidcProviderArn, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
          'token.actions.githubusercontent.com:sub': `repo:${props.githubRepo}:ref:refs/heads/main`,
        },
      }),
      maxSessionDuration: cdk.Duration.hours(1),
    });
    deployRole.addToPolicy(new iam.PolicyStatement({
      actions: ['ssm:SendCommand'],
      resources: [
        this.formatArn({ service: 'ec2', resource: 'instance', resourceName: instance.instanceId }),
        this.formatArn({ service: 'ssm', account: '', resource: 'document', resourceName: 'AWS-RunShellScript' }),
      ],
    }));
    // ListCommandInvocations has no resource-level permissions; it only reads command status and output
    deployRole.addToPolicy(new iam.PolicyStatement({ actions: ['ssm:ListCommandInvocations'], resources: ['*'] }));

    new cdk.CfnOutput(this, 'PublicIp', { value: eip.attrPublicIp });
    new cdk.CfnOutput(this, 'InstanceId', { value: instance.instanceId });
    new cdk.CfnOutput(this, 'DeployRoleArn', { value: deployRole.roleArn });
  }
}
