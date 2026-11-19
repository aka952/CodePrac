#!/usr/bin/env bash
# Deploys one commit to the ECS stack. Run by GitHub Actions (.github/workflows/ci.yml) after the tests pass, with
# the deploy role's credentials; also works from a laptop signed in to the account.   Usage:  deploy/aws/deploy.sh <tag>
#
#   1. build the five images and push them to ECR, tagged with the commit
#   2. one-off tasks with the new images: create missing database logins, then seed the content.
#      A bad exercise file fails here, while the old version keeps serving.
#   3. cdk deploy with the new tag: ECS replaces the tasks of each service one by one (rolling update; a service whose
#      new tasks fail their health checks is rolled back)
#   4. upload the frontend to S3 and clear CloudFront's cache
set -euo pipefail

tag="${1:?usage: deploy.sh <image tag, e.g. the git commit>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
stack=CodecompStack

output() { aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
registry="$(output ImageRegistry)"
cluster="$(output ClusterName)"
subnets="$(output PrivateSubnets)"
security_group="$(output OneOffSecurityGroup)"

echo "==> Building and pushing images ($tag)"
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry"
for service in identity exercise repo run worker; do
  docker build --platform linux/amd64 --target "$service" -t "$registry/codecomp-$service:$tag" "$root/backend"
  docker push "$registry/codecomp-$service:$tag"
done

# Registers a revision of a one-off task definition with the given image (or the same image), runs it, and fails
# the deploy unless it exits with 0.
run_once() {
  local family="$1" image="${2:-}"
  local definition
  definition="$(aws ecs describe-task-definition --task-definition "$family" --query taskDefinition)"
  if [ -n "$image" ]; then
    definition="$(jq --arg image "$image" '.containerDefinitions[0].image = $image' <<<"$definition")"
  fi
  local arn
  arn="$(jq '{family, taskRoleArn, executionRoleArn, networkMode, containerDefinitions, requiresCompatibilities, cpu, memory}' <<<"$definition" |
    aws ecs register-task-definition --cli-input-json file:///dev/stdin --query taskDefinition.taskDefinitionArn --output text)"
  local task
  task="$(aws ecs run-task --cluster "$cluster" --launch-type FARGATE --task-definition "$arn" \
    --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$security_group],assignPublicIp=DISABLED}" \
    --query 'tasks[0].taskArn' --output text)"
  echo "    $family: $task"
  aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$task"
  local code
  code="$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task" --query 'tasks[0].containers[0].exitCode' --output text)"
  if [ "$code" != 0 ]; then
    echo "$family failed (exit code $code). Logs: CloudWatch /codecomp/${family#codecomp-}" >&2
    exit 1
  fi
}

echo "==> Database logins"
run_once codecomp-db-init
echo "==> Seeding content"
run_once codecomp-exercise-seed "$registry/codecomp-exercise:$tag"
run_once codecomp-repo-seed "$registry/codecomp-repo:$tag"

echo "==> Updating the services"
(cd "$root/deploy/aws" && npm ci --silent && npx cdk deploy "$stack" -c imageTag="$tag" --require-approval never)

echo "==> Frontend"
(cd "$root/frontend" && npm ci --silent && npm run build)
aws s3 sync "$root/frontend/dist" "s3://$(output SiteBucket)" --delete
aws cloudfront create-invalidation --distribution-id "$(output DistributionId)" --paths '/*' >/dev/null

echo "Deployed $tag: $(output SiteUrl)"
