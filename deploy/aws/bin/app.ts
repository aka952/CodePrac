#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { CodecompStack } from '../lib/codecomp-stack';

const app = new cdk.App();
new CodecompStack(app, 'CodecompStack', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },
  githubRepo: app.node.getContext('githubRepo'),
  instanceType: app.node.getContext('instanceType'),
  // Set with `-c githubOidcProviderArn=...` when the account already has GitHub's OIDC provider (only one is allowed).
  githubOidcProviderArn: app.node.tryGetContext('githubOidcProviderArn'),
});
