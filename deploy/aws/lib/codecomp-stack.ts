import * as cdk from 'aws-cdk-lib';
import * as autoscaling from 'aws-cdk-lib/aws-autoscaling';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export interface CodecompStackProps extends cdk.StackProps {
  /** owner/name of the GitHub repository allowed to deploy, e.g. aka952/CodePrac */
  githubRepo: string;
  /** ARN of an existing GitHub OIDC provider in this account; one is created when absent */
  githubOidcProviderArn?: string;
  /**
   * The image tag to run (the git commit the pipeline built). Absent on the very first deploy, before any image has
   * been pushed: the stack is created with every service at 0 tasks, and the pipeline then deploys a real tag.
   */
  imageTag?: string;
  /** Google OAuth client id. The secret is in Secrets Manager (codecomp/google-client-secret). */
  googleClientId?: string;
  /**
   * The AWS-managed prefix list of CloudFront's origin-facing addresses in this region, so only CloudFront can reach
   * the load balancer: aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing
   */
  cloudFrontPrefixListId: string;
}

/** The four web services: their name is their image repository, Service Connect name and log group. */
type WebService = 'identity' | 'exercise' | 'repo' | 'run';

/**
 * The microservices on AWS:
 *
 *   CloudFront ─ /*     → S3 (frontend)
 *              └ /api/* → ALB ─ path rules → identity | exercise | repo | run   (ECS on Fargate, private subnets)
 *   repo ─ Service Connect → run /internal/runs
 *   run ─ SQS run-requests → worker (ECS on an EC2 Auto Scaling group: needs Docker and gVisor, which Fargate lacks)
 *   worker ─ SNS run-events → SQS run-events-run (run-service), SQS run-events-repo (repo-service)
 *   RDS Postgres: one database and one login per service
 */
export class CodecompStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: CodecompStackProps) {
    super(scope, id, props);
    const tag = props.imageTag;
    const desired = (n: number) => (tag ? n : 0);

    // ---- network ----
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 1,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC },
        { name: 'private', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      ],
    });

    // ---- data ----
    const dbSg = new ec2.SecurityGroup(this, 'DbSg', { vpc, description: 'codecomp: Postgres, from the services only' });
    const db = new rds.DatabaseInstance(this, 'Db', {
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [dbSg],
      credentials: rds.Credentials.fromGeneratedSecret('postgres'),
      allocatedStorage: 20,
      storageEncrypted: true,
      backupRetention: cdk.Duration.days(7),
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
    });
    // one login per service, so each can reach only its own database (created by the db-init task)
    const dbPasswords = Object.fromEntries((['identity', 'exercise', 'repo', 'run'] as const).map(name => [name,
      new secretsmanager.Secret(this, `${name}DbPassword`, {
        secretName: `codecomp/db/${name}`,
        generateSecretString: { excludePunctuation: true, passwordLength: 32 },
      })])) as Record<WebService, secretsmanager.Secret>;

    // set by hand after the first deploy (docs/DEPLOY_AWS.md); placeholders until then
    const jwtKey = new secretsmanager.Secret(this, 'JwtPrivateKey', {
      secretName: 'codecomp/jwt-private-key', description: 'RSA private key (PKCS#8 PEM) that signs sign-in tokens',
    });
    const googleSecret = new secretsmanager.Secret(this, 'GoogleClientSecret', { secretName: 'codecomp/google-client-secret' });
    const anthropicKey = new secretsmanager.Secret(this, 'AnthropicApiKey', { secretName: 'codecomp/anthropic-api-key' });

    // ---- messaging ----
    const queueWithDlq = (name: string, visibility: cdk.Duration, maxReceiveCount: number) => {
      const dlq = new sqs.Queue(this, `${name}Dlq`, { queueName: `${name}-dlq`, retentionPeriod: cdk.Duration.days(14) });
      const queue = new sqs.Queue(this, name, {
        queueName: name, visibilityTimeout: visibility, deadLetterQueue: { queue: dlq, maxReceiveCount },
      });
      return { queue, dlq };
    };
    // a run longer than the visibility timeout would go to a second worker: well above the slowest run (20 s)
    const runRequests = queueWithDlq('run-requests', cdk.Duration.minutes(2), 3);
    const runEvents = new sns.Topic(this, 'RunEvents', { topicName: 'run-events' });
    const runEventsForRun = queueWithDlq('run-events-run', cdk.Duration.seconds(30), 5);
    const runEventsForRepo = queueWithDlq('run-events-repo', cdk.Duration.seconds(30), 5);
    for (const { queue } of [runEventsForRun, runEventsForRepo]) {
      runEvents.addSubscription(new subscriptions.SqsSubscription(queue, { rawMessageDelivery: true }));
    }

    // ---- cluster ----
    const cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
      defaultCloudMapNamespace: { name: 'codecomp.internal', useForServiceConnect: true },
    });
    const repositories = Object.fromEntries((['identity', 'exercise', 'repo', 'run', 'worker'] as const).map(name => [name,
      new ecr.Repository(this, `${name}Images`, {
        repositoryName: `codecomp-${name}`,
        imageTagMutability: ecr.TagMutability.IMMUTABLE,
        lifecycleRules: [{ maxImageCount: 20 }],
      })])) as Record<WebService | 'worker', ecr.Repository>;
    const image = (name: WebService | 'worker') => ecs.ContainerImage.fromEcrRepository(repositories[name], tag ?? 'none');
    const logGroup = (name: string) => new logs.LogGroup(this, `${name}Logs`, {
      logGroupName: `/codecomp/${name}`, retention: logs.RetentionDays.ONE_MONTH, removalPolicy: cdk.RemovalPolicy.DESTROY,
    });
    const dbUrl = (name: WebService) => `jdbc:postgresql://${db.dbInstanceEndpointAddress}:${db.dbInstanceEndpointPort}/${name}`;
    const jwksUri = 'http://identity:8080/api/auth/jwks';
    const awsEnv = { AWS_REGION: this.region };

    // ---- load balancer ----
    const albSg = new ec2.SecurityGroup(this, 'AlbSg', { vpc, description: 'codecomp: load balancer' });
    albSg.addIngressRule(ec2.Peer.prefixList(props.cloudFrontPrefixListId), ec2.Port.tcp(80), 'CloudFront only');
    const alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', { vpc, internetFacing: true, securityGroup: albSg });
    const listener = alb.addListener('Http', {
      port: 80,
      open: false,
      // no rule matched (including every /internal path): not found
      defaultAction: elbv2.ListenerAction.fixedResponse(404, { contentType: 'application/json', messageBody: '{"detail":"Not found"}' }),
    });

    // ---- web services (Fargate) ----
    const securityGroups = {} as Record<WebService, ec2.SecurityGroup>;
    const services = {} as Record<WebService, ecs.FargateService>;
    const webService = (name: WebService, opts: {
      paths: string[]; priority: number; environment: Record<string, string>;
      secrets?: Record<string, ecs.Secret>; minTasks: number; maxTasks: number; sticky?: boolean;
    }) => {
      const task = new ecs.FargateTaskDefinition(this, `${name}Task`, {
        family: `codecomp-${name}`, cpu: 512, memoryLimitMiB: 1024,
        runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
      });
      task.addContainer('app', {
        image: image(name),
        portMappings: [{ name: 'http', containerPort: 8080 }],
        environment: { DB_URL: dbUrl(name), DB_USER: name, ...opts.environment },
        secrets: { DB_PASSWORD: ecs.Secret.fromSecretsManager(dbPasswords[name]), ...opts.secrets },
        // health is checked by the load balancer's target group (/actuator/health), which ECS uses to replace tasks
        logging: ecs.LogDrivers.awsLogs({ streamPrefix: name, logGroup: logGroup(name) }),
      });
      const sg = new ec2.SecurityGroup(this, `${name}Sg`, { vpc, description: `codecomp: ${name}-service` });
      sg.addIngressRule(albSg, ec2.Port.tcp(8080), 'from the load balancer');
      dbSg.addIngressRule(sg, ec2.Port.tcp(5432), `${name}-service`);
      const service = new ecs.FargateService(this, `${name}Service`, {
        cluster, taskDefinition: task, serviceName: name, desiredCount: desired(opts.minTasks),
        securityGroups: [sg], vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
        circuitBreaker: { enable: true, rollback: true },
        minHealthyPercent: 100, maxHealthyPercent: 200,
        // Spring Boot plus Flyway needs time before the first health check counts
        healthCheckGracePeriod: cdk.Duration.seconds(90),
        serviceConnectConfiguration: { services: [{ portMappingName: 'http', dnsName: name, port: 8080 }] },
      });
      const scaling = service.autoScaleTaskCount({ minCapacity: desired(opts.minTasks), maxCapacity: tag ? opts.maxTasks : 0 });
      scaling.scaleOnCpuUtilization('Cpu', { targetUtilizationPercent: 60 });
      const target = listener.addTargets(name, {
        priority: opts.priority,
        conditions: [elbv2.ListenerCondition.pathPatterns(opts.paths)],
        port: 8080, protocol: elbv2.ApplicationProtocol.HTTP,
        targets: [service],
        healthCheck: { path: '/actuator/health', healthyHttpCodes: '200' },
        deregistrationDelay: cdk.Duration.seconds(30),
        // the only session in the system: the few seconds of the redirect to Google and back
        stickinessCookieDuration: opts.sticky ? cdk.Duration.minutes(5) : undefined,
      });
      securityGroups[name] = sg;
      services[name] = service;
      return { task, service, target };
    };

    webService('identity', {
      paths: ['/api/auth/*'], priority: 10, minTasks: 1, maxTasks: 2, sticky: true,
      environment: { GOOGLE_CLIENT_ID: props.googleClientId ?? '' },
      secrets: {
        JWT_PRIVATE_KEY: ecs.Secret.fromSecretsManager(jwtKey),
        GOOGLE_CLIENT_SECRET: ecs.Secret.fromSecretsManager(googleSecret),
      },
    });
    webService('exercise', {
      paths: ['/api/exercises', '/api/exercises/*', '/api/points/snippets'], priority: 20, minTasks: 1, maxTasks: 4,
      environment: { JWKS_URI: jwksUri },
    });
    const repo = webService('repo', {
      paths: ['/api/repos', '/api/repos/*', '/api/edits', '/api/points/repos'], priority: 30, minTasks: 1, maxTasks: 4,
      environment: {
        ...awsEnv, JWKS_URI: jwksUri, RUN_SERVICE_URL: 'http://run:8080', RUN_EVENTS_QUEUE_URL: runEventsForRepo.queue.queueUrl,
      },
      secrets: { ANTHROPIC_API_KEY: ecs.Secret.fromSecretsManager(anthropicKey) },
    });
    const run = webService('run', {
      paths: ['/api/runs', '/api/jobs/*', '/api/languages'], priority: 40, minTasks: 1, maxTasks: 4,
      environment: {
        ...awsEnv, JWKS_URI: jwksUri,
        RUN_REQUESTS_QUEUE_URL: runRequests.queue.queueUrl, RUN_REQUESTS_DLQ_URL: runRequests.dlq.queueUrl,
        RUN_EVENTS_TOPIC_ARN: runEvents.topicArn, RUN_EVENTS_QUEUE_URL: runEventsForRun.queue.queueUrl,
      },
    });
    // who may call whom inside the network: everyone checks tokens against identity's keys; only repo calls run
    for (const caller of ['exercise', 'repo', 'run'] as const) {
      securityGroups.identity.addIngressRule(securityGroups[caller], ec2.Port.tcp(8080), `${caller} fetches the JWKS`);
    }
    securityGroups.run.addIngressRule(securityGroups.repo, ec2.Port.tcp(8080), 'repo submits test runs');
    // run-service holds SSE streams open for up to 45 s: let them end before a task is removed in a deploy
    run.target.setAttribute('deregistration_delay.timeout_seconds', '60');

    runRequests.queue.grantSendMessages(run.task.taskRole);
    runEvents.grantPublish(run.task.taskRole); // cache hits for repo-service
    runEventsForRun.queue.grantConsumeMessages(run.task.taskRole);
    runRequests.dlq.grantConsumeMessages(run.task.taskRole);
    runEventsForRepo.queue.grantConsumeMessages(repo.task.taskRole);

    // ---- sandbox workers (EC2: Docker + gVisor) ----
    const workerHosts = new autoscaling.AutoScalingGroup(this, 'WorkerHosts', {
      vpc, vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      instanceType: new ec2.InstanceType('t3.medium'),
      machineImage: ecs.EcsOptimizedImage.amazonLinux2023(ecs.AmiHardwareType.STANDARD),
      minCapacity: 0, maxCapacity: 4,
      requireImdsv2: true,
      blockDevices: [{ deviceName: '/dev/xvda', volume: autoscaling.BlockDeviceVolume.ebs(30, { encrypted: true }) }],
    });
    workerHosts.addUserData(
      'set -euo pipefail',
      // gVisor: user code runs in a user-space kernel, not directly on the host's
      'url=https://storage.googleapis.com/gvisor/releases/release/latest/$(uname -m)',
      'cd /tmp && curl -fsSLO "$url/runsc" -O "$url/runsc.sha512" && sha512sum -c runsc.sha512',
      'install -m 0755 runsc /usr/local/bin/runsc && /usr/local/bin/runsc install',
      'systemctl restart docker --no-block',
      // the worker also pulls these at startup; doing it here makes a new host ready sooner
      '(sleep 20 && docker pull -q python:3.12-alpine && docker pull -q eclipse-temurin:21-jdk-alpine) &',
    );
    const capacity = new ecs.AsgCapacityProvider(this, 'WorkerCapacity', { autoScalingGroup: workerHosts, enableManagedScaling: true });
    cluster.addAsgCapacityProvider(capacity);

    const workerTask = new ecs.Ec2TaskDefinition(this, 'workerTask', { family: 'codecomp-worker' });
    workerTask.addVolume({ name: 'docker', host: { sourcePath: '/var/run/docker.sock' } });
    const workerContainer = workerTask.addContainer('app', {
      image: image('worker'),
      memoryReservationMiB: 512,
      environment: {
        ...awsEnv, RUN_REQUESTS_QUEUE_URL: runRequests.queue.queueUrl, RUN_EVENTS_TOPIC_ARN: runEvents.topicArn,
        SANDBOX_RUNTIME: 'runsc', SANDBOX_MAX_CONCURRENT: '4',
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'worker', logGroup: logGroup('worker') }),
      // time to finish its current receive and the runs in progress (see JobWorker.stop)
      stopTimeout: cdk.Duration.seconds(60),
    });
    workerContainer.addMountPoints({ sourceVolume: 'docker', containerPath: '/var/run/docker.sock', readOnly: false });
    runRequests.queue.grantConsumeMessages(workerTask.taskRole);
    runEvents.grantPublish(workerTask.taskRole);
    const worker = new ecs.Ec2Service(this, 'workerService', {
      cluster, taskDefinition: workerTask, serviceName: 'worker', desiredCount: desired(1),
      capacityProviderStrategies: [{ capacityProvider: capacity.capacityProviderName, weight: 1 }],
      // one worker per host: each one owns its host's Docker daemon and sandbox slots
      placementConstraints: [ecs.PlacementConstraint.distinctInstances()],
      circuitBreaker: { enable: true, rollback: true },
      minHealthyPercent: 0,
    });
    // more workers when runs wait in the queue: the signal is queue depth, not CPU
    worker.autoScaleTaskCount({ minCapacity: desired(1), maxCapacity: tag ? 4 : 0 }).scaleOnMetric('QueueDepth', {
      metric: runRequests.queue.metricApproximateNumberOfMessagesVisible({ period: cdk.Duration.minutes(1) }),
      scalingSteps: [{ upper: 0, change: -1 }, { lower: 10, change: +1 }, { lower: 50, change: +2 }],
      adjustmentType: autoscaling.AdjustmentType.CHANGE_IN_CAPACITY,
      cooldown: cdk.Duration.minutes(2),
    });

    // ---- one-off tasks, run by the pipeline before the services update (deploy/aws/deploy.sh) ----
    const oneOffSg = new ec2.SecurityGroup(this, 'OneOffSg', { vpc, description: 'codecomp: db-init and seed tasks' });
    dbSg.addIngressRule(oneOffSg, ec2.Port.tcp(5432), 'db-init and seed tasks');
    const dbInit = new ecs.FargateTaskDefinition(this, 'dbInitTask', { family: 'codecomp-db-init', cpu: 256, memoryLimitMiB: 512 });
    dbInit.addContainer('app', {
      image: ecs.ContainerImage.fromRegistry('postgres:16-alpine'),
      // creates each service's login and database if missing; safe to run on every deploy
      command: ['sh', '-c', [
        'for s in identity exercise repo run; do',
        '  pw=$(printenv "PW_$s")',
        '  psql -v ON_ERROR_STOP=1 -c "do \\$\\$ begin if not exists (select from pg_roles where rolname = \'$s\') then create role $s login; end if; end \\$\\$;"',
        '  psql -v ON_ERROR_STOP=1 -c "alter role $s password \'$pw\'"',
        '  psql -tc "select 1 from pg_database where datname = \'$s\'" | grep -q 1 || psql -v ON_ERROR_STOP=1 -c "create database $s owner $s"',
        '  psql -v ON_ERROR_STOP=1 -c "revoke connect on database $s from public"',
        'done',
      ].join('\n')],
      environment: { PGHOST: db.dbInstanceEndpointAddress, PGPORT: db.dbInstanceEndpointPort, PGUSER: 'postgres', PGDATABASE: 'postgres' },
      secrets: {
        PGPASSWORD: ecs.Secret.fromSecretsManager(db.secret!, 'password'),
        ...Object.fromEntries(Object.entries(dbPasswords).map(([s, secret]) => [`PW_${s}`, ecs.Secret.fromSecretsManager(secret)])),
      },
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'db-init', logGroup: logGroup('db-init') }),
    });
    for (const name of ['exercise', 'repo'] as const) {
      const seed = new ecs.FargateTaskDefinition(this, `${name}SeedTask`, { family: `codecomp-${name}-seed`, cpu: 256, memoryLimitMiB: 1024 });
      seed.addContainer('app', {
        image: image(name),
        environment: { SPRING_PROFILES_ACTIVE: 'seed', DB_URL: dbUrl(name), DB_USER: name },
        secrets: { DB_PASSWORD: ecs.Secret.fromSecretsManager(dbPasswords[name]) },
        logging: ecs.LogDrivers.awsLogs({ streamPrefix: `${name}-seed`, logGroup: logGroup(`${name}-seed`) }),
      });
    }

    // ---- frontend ----
    const site = new s3.Bucket(this, 'Site', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL, enforceSSL: true, encryption: s3.BucketEncryption.S3_MANAGED,
      removalPolicy: cdk.RemovalPolicy.DESTROY, autoDeleteObjects: true,
    });
    const api = new origins.LoadBalancerV2Origin(alb, {
      protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
      // SSE streams send an event at least every 45 s (see JobEvents), inside this
      readTimeout: cdk.Duration.seconds(60),
    });
    const cdn = new cloudfront.Distribution(this, 'Cdn', {
      defaultRootObject: 'index.html',
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(site),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
      },
      additionalBehaviors: {
        '/api/*': {
          origin: api,
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
          cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
          originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER,
        },
      },
      // client-side routes (/repos/x) load the app, which then routes itself
      errorResponses: [{ httpStatus: 403, responseHttpStatus: 200, responsePagePath: '/index.html' }],
    });

    // ---- alarms ----
    for (const [name, dlq] of [['RunRequests', runRequests.dlq], ['RunEventsRun', runEventsForRun.dlq], ['RunEventsRepo', runEventsForRepo.dlq]] as const) {
      new cloudwatch.Alarm(this, `${name}DlqAlarm`, {
        metric: dlq.metricApproximateNumberOfMessagesVisible(), threshold: 1, evaluationPeriods: 1,
        alarmDescription: `Messages in ${dlq.queueName}: runs or events that could not be processed`,
      });
    }
    new cloudwatch.Alarm(this, 'Api5xxAlarm', {
      metric: alb.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { period: cdk.Duration.minutes(5) }),
      threshold: 10, evaluationPeriods: 1, treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // ---- GitHub Actions deploy role: main branch only ----
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
    Object.values(repositories).forEach(r => r.grantPullPush(deployRole));
    site.grantReadWrite(deployRole);
    site.grantDelete(deployRole);
    deployRole.addToPolicy(new iam.PolicyStatement({
      actions: ['cloudfront:CreateInvalidation'],
      resources: [`arn:aws:cloudfront::${this.account}:distribution/${cdn.distributionId}`],
    }));
    // the one-off tasks: register a revision with the new image, run it, wait for it
    deployRole.addToPolicy(new iam.PolicyStatement({
      actions: ['ecs:DescribeTaskDefinition', 'ecs:RegisterTaskDefinition', 'ecs:RunTask', 'ecs:DescribeTasks', 'cloudformation:DescribeStacks'],
      resources: ['*'],
    }));
    deployRole.addToPolicy(new iam.PolicyStatement({
      actions: ['iam:PassRole'],
      resources: [dbInit, ...['exercise', 'repo'].map(n => this.node.findChild(`${n}SeedTask`) as ecs.FargateTaskDefinition)]
        .flatMap(t => [t.taskRole.roleArn, t.obtainExecutionRole().roleArn]),
    }));
    // cdk deploy works through the roles `cdk bootstrap` created
    deployRole.addToPolicy(new iam.PolicyStatement({
      actions: ['sts:AssumeRole'], resources: [`arn:aws:iam::${this.account}:role/cdk-*`],
    }));

    // ---- outputs (read by deploy/aws/deploy.sh) ----
    const output = (name: string, value: string) => new cdk.CfnOutput(this, name, { value });
    output('SiteUrl', `https://${cdn.distributionDomainName}`);
    output('SiteBucket', site.bucketName);
    output('DistributionId', cdn.distributionId);
    output('ClusterName', cluster.clusterName);
    output('PrivateSubnets', vpc.privateSubnets.map(s => s.subnetId).join(','));
    output('OneOffSecurityGroup', oneOffSg.securityGroupId);
    output('ImageRegistry', `${this.account}.dkr.ecr.${this.region}.amazonaws.com`);
    output('DeployRoleArn', deployRole.roleArn);
  }
}
