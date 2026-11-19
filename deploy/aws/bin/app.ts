#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { CodecompStack } from '../lib/codecomp-stack';

const app = new cdk.App();
new CodecompStack(app, 'CodecompStack', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },
  githubRepo: app.node.getContext('githubRepo'),
  // Set with `-c githubOidcProviderArn=...` when the account already has GitHub's OIDC provider (only one is allowed).
  githubOidcProviderArn: app.node.tryGetContext('githubOidcProviderArn'),
  // Set by deploy/aws/deploy.sh to the commit it built. Absent on the first deploy: every service starts at 0 tasks.
  imageTag: app.node.tryGetContext('imageTag'),
  googleClientId: app.node.tryGetContext('googleClientId'),
  cloudFrontPrefixListId: app.node.getContext('cloudFrontPrefixListId'),
});
