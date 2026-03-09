def call(Map config = [:]) {

    pipeline {
        agent any

        stages {

            stage('Initialize') {
                steps {
                    script {
                        env.DOCKER_IMAGE = config.image
                        env.DOCKER_TAG = env.BUILD_NUMBER
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
                    sh "docker scout cves ${env.DOCKER_IMAGE}:${env.DOCKER_TAG} || true"
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

            stage('Deploy Dev') {
                when { branch 'develop' }
                steps {
                    echo "Deploying to Dev"
                }
            }

            stage('Deploy Staging') {
                when { expression { env.BRANCH_NAME.startsWith('release/') } }
                steps {
                    echo "Deploying to Staging"
                }
            }

            stage('Deploy Production') {
                when { branch 'main' }
                steps {
                    input "Approve production deployment?"
                    echo "Deploying to Production"
                }
            }

        }
    }

}