
pipeline {
    agent {
        label 'docker-build'
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        timestamps()
    }

    tools {
        maven 'Maven-3'
    }

    environment {
        MAVEN_OPTS = '-Xms128m -Xmx512m'

        IMAGE_NAME = 'ruoyi-backend'

        AWS_REGION = 'ap-northeast-1'
        ECR_REGISTRY = '555066174866.dkr.ecr.ap-northeast-1.amazonaws.com'
        ECR_REPOSITORY = 'ruoyi-stg-ruoyi'

        ECS_CLUSTER = 'ruoyi-stg-ecs'
        ECS_SERVICE = 'ruoyi-stg-ruoyi-service'
        ECS_CONTAINER = 'ruoyi'
    }

    stages {

        // ==========================================
        // Stage 1 - Git Checkout
        // ==========================================
        stage('Git Checkout') {
            steps {
                checkout scm

                sh '''
                    echo "Current Git Commit:"
                    git rev-parse HEAD
                '''
            }
        }

        // ==========================================
        // Stage 2 - Maven Build
        // ==========================================
        stage('Maven Build') {
            steps {
                sh '''
                    mvn -B -ntp -T 1 clean package -DskipTests
                '''
            }
        }

        // ==========================================
        // Stage 3 - Verify JAR
        // ==========================================
        stage('Verify JAR') {
            steps {
                sh '''
                    ls -lh ruoyi-admin/target/ruoyi-admin.jar
                '''
            }
        }

        // ==========================================
        // Stage 4 - Docker Build
        // ==========================================
        stage('Docker Build') {
            steps {
                sh '''
                    docker build \
                      -t "${IMAGE_NAME}:${BUILD_NUMBER}" \
                      .
                '''
            }
        }

        // ==========================================
        // Stage 5 - ECR Push
        // ==========================================
        stage('ECR Push') {
            steps {
                sh '''#!/bin/bash
set -euo pipefail

export DOCKER_CONFIG="$(mktemp -d)"
trap 'rm -rf "$DOCKER_CONFIG"' EXIT

echo "Logging in to Amazon ECR..."

aws ecr get-login-password \
  --region "$AWS_REGION" |
docker login \
  --username AWS \
  --password-stdin "$ECR_REGISTRY"

echo "Tagging Docker image..."

docker tag \
  "${IMAGE_NAME}:${BUILD_NUMBER}" \
  "${ECR_REGISTRY}/${ECR_REPOSITORY}:${BUILD_NUMBER}"

echo "Pushing Docker image to Amazon ECR..."

docker push \
  "${ECR_REGISTRY}/${ECR_REPOSITORY}:${BUILD_NUMBER}"

echo "ECR Push completed."
'''
            }
        }

        // ==========================================
        // Stage 6 - Verify ECR Image
        // ==========================================
        stage('Verify ECR Image') {
            steps {
                sh '''
                    aws ecr describe-images \
                      --region "$AWS_REGION" \
                      --repository-name "$ECR_REPOSITORY" \
                      --image-ids "imageTag=$BUILD_NUMBER" \
                      --query 'imageDetails[0].[imageDigest,imageTags]' \
                      --output json
                '''
            }
        }

        // ==========================================
        // Stage 7 - Prepare Task Definition
        // ==========================================
        stage('Prepare Task Definition') {
            steps {
                sh '''
                    set -eu

                    mkdir -p .ecs

                    CURRENT_TD=$(aws ecs describe-services \
                      --cluster "$ECS_CLUSTER" \
                      --services "$ECS_SERVICE" \
                      --region "$AWS_REGION" \
                      --query 'services[0].taskDefinition' \
                      --output text)

                    echo "Current Task Definition: $CURRENT_TD"

                    aws ecs describe-task-definition \
                      --task-definition "$CURRENT_TD" \
                      --region "$AWS_REGION" \
                      --query 'taskDefinition' \
                      --output json > .ecs/current-task.json

                    python3 - <<'PY'
import json
import os

with open(".ecs/current-task.json", encoding="utf-8") as f:
    current = json.load(f)

# Only retain parameters supported by RegisterTaskDefinition
allowed = [
    "family",
    "taskRoleArn",
    "executionRoleArn",
    "networkMode",
    "containerDefinitions",
    "volumes",
    "placementConstraints",
    "requiresCompatibilities",
    "cpu",
    "memory",
    "pidMode",
    "ipcMode",
    "proxyConfiguration",
    "inferenceAccelerators",
    "ephemeralStorage",
    "runtimePlatform",
    "enableFaultInjection"
]

new_task = {
    key: current[key]
    for key in allowed
    if key in current and current[key] is not None
}

# Construct new ECR image URI
image = (
    os.environ["ECR_REGISTRY"]
    + "/"
    + os.environ["ECR_REPOSITORY"]
    + ":"
    + os.environ["BUILD_NUMBER"]
)

updated = False

# Replace only the RuoYi container image
for container in new_task["containerDefinitions"]:
    if container["name"] == os.environ["ECS_CONTAINER"]:
        container["image"] = image
        updated = True

if not updated:
    raise RuntimeError("ECS container not found")

# Preserve existing DB / Redis / Secrets / Logs configuration
with open(".ecs/new-task.json", "w", encoding="utf-8") as f:
    json.dump(new_task, f, indent=2)

print("New ECS image:", image)
print("Task Definition prepared")
PY
                '''
            }
        }

        // ==========================================
        // Stage 8 - Register Task Definition
        // ==========================================
        stage('Register Task Definition') {
            steps {
                sh '''
                    set -eu

                    NEW_TD=$(aws ecs register-task-definition \
                      --cli-input-json file://.ecs/new-task.json \
                      --region "$AWS_REGION" \
                      --query 'taskDefinition.taskDefinitionArn' \
                      --output text)

                    echo "$NEW_TD" > .ecs/new-task-arn.txt

                    echo "Registered Task Definition:"
                    echo "$NEW_TD"
                '''
            }
        }

        // ==========================================
        // Stage 9 - Deploy to ECS
        // ==========================================
        stage('Deploy to ECS') {
            steps {
                sh '''
                    set -eu

                    NEW_TD=$(cat .ecs/new-task-arn.txt)

                    echo "Deploying Task Definition: $NEW_TD"

                    aws ecs update-service \
                      --cluster "$ECS_CLUSTER" \
                      --service "$ECS_SERVICE" \
                      --task-definition "$NEW_TD" \
                      --desired-count 1 \
                      --region "$AWS_REGION" \
                      --query 'service.[serviceName,desiredCount,taskDefinition]' \
                      --output table
                '''
            }
        }

        // ==========================================
        // Stage 10 - Wait for ECS Stable
        // ==========================================
        stage('Wait for ECS Stable') {
            steps {
                timeout(time: 15, unit: 'MINUTES') {
                    sh '''
                        set -eu

                        echo "Waiting for ECS Service to become stable..."

                        aws ecs wait services-stable \
                          --cluster "$ECS_CLUSTER" \
                          --services "$ECS_SERVICE" \
                          --region "$AWS_REGION"

                        echo "ECS Service is stable."
                    '''
                }
            }
        }

        // ==========================================
        // Stage 11 - Verify ECS Deployment
        // ==========================================
        stage('Verify ECS Deployment') {
            steps {
                sh '''
                    set -eu

                    EXPECTED_TD=$(cat .ecs/new-task-arn.txt)

                    ACTUAL_TD=$(aws ecs describe-services \
                      --cluster "$ECS_CLUSTER" \
                      --services "$ECS_SERVICE" \
                      --region "$AWS_REGION" \
                      --query 'services[0].taskDefinition' \
                      --output text)

                    RUNNING=$(aws ecs describe-services \
                      --cluster "$ECS_CLUSTER" \
                      --services "$ECS_SERVICE" \
                      --region "$AWS_REGION" \
                      --query 'services[0].runningCount' \
                      --output text)

                    echo "Expected Task Definition: $EXPECTED_TD"
                    echo "Actual Task Definition:   $ACTUAL_TD"
                    echo "Running Tasks:            $RUNNING"

                    test "$EXPECTED_TD" = "$ACTUAL_TD"
                    test "$RUNNING" = "1"

                    echo "ECS Deployment verified!"
                '''
            }
        }
    }

    // ==========================================
    // Post Actions
    // ==========================================
    post {
        success {
            echo 'RuoYi CI/CD Pipeline SUCCESS!'

            archiveArtifacts artifacts: 'ruoyi-admin/target/*.jar',
                             fingerprint: true
        }

        failure {
            echo 'RuoYi CI/CD Pipeline FAILED!'
        }
    }
}
