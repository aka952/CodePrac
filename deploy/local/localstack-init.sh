#!/bin/bash
# Local development only: creates the same queues and topic as the CDK stack (deploy/aws/lib/codecomp-stack.ts).
# LocalStack runs this once it is ready; services wait for /tmp/messaging-ready.
set -euo pipefail

account=000000000000
region=us-east-1
arn() { echo "arn:aws:sqs:${region}:${account}:$1"; }

queue_with_dlq() { # name visibility-seconds max-receives
  awslocal sqs create-queue --queue-name "$1-dlq" >/dev/null
  awslocal sqs create-queue --queue-name "$1" --attributes "{
    \"VisibilityTimeout\": \"$2\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$(arn "$1-dlq")\\\",\\\"maxReceiveCount\\\":\\\"$3\\\"}\"
  }" >/dev/null
}

# work queue: a run longer than the visibility timeout would be handed to a second worker, so it is well above the
# slowest run (20 s); after 3 receives without a result the request goes to the dead-letter queue
queue_with_dlq run-requests 120 3

# one queue per subscriber to the run-events topic (fan-out)
topic=$(awslocal sns create-topic --name run-events --query TopicArn --output text)
for subscriber in run repo; do
  queue_with_dlq "run-events-$subscriber" 30 5
  awslocal sns subscribe --topic-arn "$topic" --protocol sqs --notification-endpoint "$(arn "run-events-$subscriber")" \
    --attributes RawMessageDelivery=true >/dev/null
done

touch /tmp/messaging-ready
echo "Queues and topic ready"
