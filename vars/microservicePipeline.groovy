def call(Map config = [:]) {

    pipeline {
        agent any

        stages {

            stage('Initialize') {
                steps {
                    script {
                        env.DOCKER_IMAGE = config.image
                        env.DOCKER_TAG = env.BUILD_NUMBER
                        env.SERVICE_NAME = config.image.tokenize('/')[1]
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        if (config.buildCommand) {
                            sh config.buildCommand
                        }
                    }
                }
            }

            stage('Test') {
                steps {
                    script {
                        if (config.testCommand) {
                            sh config.testCommand
                        }
                    }
                }
            }

            stage('Container Build') {
                steps {
                    sh "docker build -t ${env.DOCKER_IMAGE}:${env.DOCKER_TAG} ."
                }
            }

            stage('Security Scan') {
                steps {
                    sh "trivy image --exit-code 0 --severity HIGH,CRITICAL ${env.DOCKER_IMAGE}:${env.DOCKER_TAG}"
                }
            }

            stage('Container Push') {
                when {
                    anyOf {
                        branch 'develop'
                        branch 'main'
                        expression { env.BRANCH_NAME.startsWith('release/') }
                    }
                }

                steps {
                    withCredentials([usernamePassword(
                        credentialsId: 'dockerhub-creds',
                        usernameVariable: 'USERNAME',
                        passwordVariable: 'PASSWORD'
                    )]) {

                        sh '''
                        echo $PASSWORD | docker login -u $USERNAME --password-stdin
                        docker push $DOCKER_IMAGE:$DOCKER_TAG
                        '''
                    }
                }
            }

            stage('Update Kubernetes Manifest') {
                steps {
                    sh """
                    rm -rf ecommerce-infrastructure
                    git clone https://github.com/grace-kluender/ecommerce-infrastructure.git
                    sed -i 's|IMAGE_TAG|${DOCKER_TAG}|g' ecommerce-infrastructure/k8s/${SERVICE_NAME}/deployment.yaml
                    """
                }
            }

            stage('Deploy Dev') {
                when { branch 'develop' }
                steps {
                    sh """
                    kubectl apply -f \$PWD/ecommerce-infrastructure/k8s/${SERVICE_NAME} -n dev --validate=false
                    """
                }
            }

            stage('Deploy Staging') {
                when { expression { env.BRANCH_NAME.startsWith('release/') } }
                steps {
                    sh """
                    kubectl apply -f \$PWD/ecommerce-infrastructure/k8s/${SERVICE_NAME} -n staging --validate=false
                    """
                    echo "Deploying to Staging"
                }
            }

            stage('Deploy Production') {
                when { branch 'main' }
                steps {
                    input "Approve production deployment?"
                    sh """
                    kubectl apply -f \$PWD/ecommerce-infrastructure/k8s/${SERVICE_NAME} -n prod --validate=false
                    """
                    echo "Deploying to Production"
                }
            }

            stage('Reset Manifest') {
                steps {
                    sh """
                    cd ecommerce-infrastructure
                    git checkout -- k8s/${SERVICE_NAME}/deployment.yaml || true
                    """
                }
            }

        }
    }
}