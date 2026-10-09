
pipeline {
    agent {
        label 'docker-build'
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout()
        timestamps()
    }

    // ==========================================
    // Build Parameters
    // ==========================================
    parameters {
        choice(
            name: 'REF_TYPE',
            choices: ['BRANCH', 'TAG'],
            description: '选择构建来源：Git Branch 或 Git Tag'
        )

        string(
            name: 'GIT_BRANCH',
            defaultValue: 'main',
            trim: true,
            description: 'BRANCH 模式填写分支名，例如 main'
        )

        string(
            name: 'GIT_TAG',
            defaultValue: '',
            trim: true,
            description: 'TAG 模式填写标签名，例如 v1.0.0'
        )

        booleanParam(
            name: 'DEPLOY_TO_ECS',
            defaultValue: false,
            description: '勾选后部署到 ECS；不勾选则只构建并推送 ECR'
        )
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
        // Stage 1 - Validate Parameters
        // ==========================================
        stage('Validate Parameters') {
            steps {
                script {
                    if (params.REF_TYPE == 'BRANCH') {
                        if (!params.GIT_BRANCH?.trim()) {
                            error('GIT_BRANCH 不能为空')
                        }
                    } else if (params.REF_TYPE == 'TAG') {
                        if (!params.GIT_TAG?.trim()) {
                            error('GIT_TAG 不能为空')
                        }
                    } else {
                        error("不支持的 REF_TYPE: ${params.REF_TYPE}")
                    }

                    echo "Git Ref Type: ${params.REF_TYPE}"
                    echo "Git Branch: ${params.GIT_BRANCH}"
                    echo "Git Tag: ${params.GIT_TAG}"
                    echo "Deploy to ECS: ${params.DEPLOY_TO_ECS}"
                }
            }
        }

        // ==========================================
        // Stage 2 - Git Checkout
        // ==========================================
        stage('Git Checkout') {
            steps {
                // Checkout SCM defined in Jenkins Job
                // Jenkinsfile is loaded from the trusted main branch.
                checkout scm

                // Fetch requested source version.
                sh '''#!/bin/bash
set -euo pipefail

echo "Fetching Git branches and tags..."

git fetch --tags origin \
  '+refs/heads/*:refs/remotes/origin/*'

if [ "$REF_TYPE" = "BRANCH" ]; then

    echo "Selected Branch: $GIT_BRANCH"

    git check-ref-format --branch "$GIT_BRANCH"

    COMMIT=$(git rev-parse --verify \
      "refs/remotes/origin/${GIT_BRANCH}^{commit}")

elif [ "$REF_TYPE" = "TAG" ]; then

    echo "Selected Tag: $GIT_TAG"

    git check-ref-format "refs/tags/$GIT_TAG"

    COMMIT=$(git rev-parse --verify \
      "refs/tags/${GIT_TAG}^{commit}")

else
    echo "Unsupported Git reference type."
    exit 1
fi

echo "Checking out Commit: $COMMIT"

git checkout --detach "$COMMIT"

echo "Resolved Git Commit:"
git rev-parse HEAD

echo "Latest Commit:"
git log -1 --format='%h %s'
'''
            }
        }

        // ==========================================
        // Stage 3 - Maven Build
        // ==========================================
        stage('Maven Build') {
            steps {
                sh '''
                    mvn -B -ntp -T 1 clean package -DskipTests
                '''
            }
        }

        // ==========================================
        // Stage 4 - Verify JAR
        // ==========================================
        stage('Verify JAR') {
            steps {
                sh '''
                    test -s ruoyi-admin/target/ruoyi-admin.jar
                    ls -lh ruoyi-admin/target/ruoyi-admin.jar
                '''
            }
        }

        // ==========================================
        // Stage 5 - Docker Build
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
        // Stage 6 - ECR Push
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

echo "Pushing Docker image..."

docker push \
  "${ECR_REGISTRY}/${ECR_REPOSITORY}:${BUILD_NUMBER}"

echo "ECR Push completed."
'''
            }
        }

        // ==========================================
        // Stage 7 - Verify ECR Image
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
        // Stage 8 - Prepare Task Definition
        // ==========================================
        stage('Prepare Task Definition') {
            when {
                expression {
                    params.DEPLOY_TO_ECS == true
                }
            }

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

# Fields accepted by RegisterTaskDefinition
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

image = (
    os.environ["ECR_REGISTRY"]
    + "/"
    + os.environ["ECR_REPOSITORY"]
    + ":"
    + os.environ["BUILD_NUMBER"]
)

updated = False

for container in new_task["containerDefinitions"]:
    if container["name"] == os.environ["ECS_CONTAINER"]:
        container["image"] = image
        updated = True

if not updated:
    raise RuntimeError("ECS container not found")

with open(".ecs/new-task.json", "w", encoding="utf-8") as f:
    json.dump(new_task, f, indent=2)

print("New ECS image:", image)
print("Task Definition prepared")
PY
                '''
            }
        }

        // ==========================================
        // Stage 9 - Register Task Definition
        // ==========================================
        stage('Register Task Definition') {
            when {
                expression {
                    params.DEPLOY_TO_ECS == true
                }
            }

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
        // Stage 10 - Deploy to ECS
        // ==========================================
        stage('Deploy to ECS') {
            when {
                expression {
                    params.DEPLOY_TO_ECS == true
                }
            }

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
        // Stage 11 - Wait for ECS Stable
        // ==========================================
        stage('Wait for ECS Stable') {
            when {
                expression {
                    params.DEPLOY_TO_ECS == true
                }
            }

            steps {
                timeout(time: 15, unit: 'MINUTES') {
                    sh '''
                        set -eu

                        echo "Waiting for ECS Service..."

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
        // Stage 12 - Verify ECS Deployment
        // ==========================================
        stage('Verify ECS Deployment') {
            when {
                expression {
                    params.DEPLOY_TO_ECS == true
                }
            }

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
