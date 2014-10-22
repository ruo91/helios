#!/bin/bash -ex

case "$1" in
  pre_machine)
    sudo apt-get install socat

    # have docker write debug logs
    docker_opts='DOCKER_OPTS="$DOCKER_OPTS -D"'
    sudo sh -c "echo '$docker_opts' >> /etc/default/docker"

    cat /etc/default/docker

    ;;

  post_machine)
    sudo chown ubuntu:ubuntu /var/log/upstart/docker.log

    ;;

  dependencies)
    # clean the artifacts dir from the previous build
    rm -rf artifacts && mkdir artifacts

    # run a docker speed tests
    wget http://cl.ly/code/0t0n3u1k2f2l/speedtest.sh
    chmod +x speedtest.sh
    ./speedtest.sh > artifacts/speedtests.log

    mvn clean install -T 2 -Dmaven.javadoc.skip=true -DskipTests=true -B -V

    # collect artifacts into the artifacts dir
    find . -regex ".*/target/.*-[0-9]\.jar" | xargs -I {} mv {} artifacts
    find . -regex ".*/target/.*-SNAPSHOT\.jar" | xargs -I {} mv {} artifacts
    find . -regex ".*/target/.*\.deb" | xargs -I {} mv {} artifacts

    ;;

  test)
    # use socat so we can log all traffic to/from docker daemon
    socat -v TCP4-LISTEN:2375,fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock &> artifacts/socat.log &
    sleep 2

    docker info

    # expected parallelism: 2x. needs to be set in the project settings via CircleCI's UI.
    case $CIRCLE_NODE_INDEX in
      0)
        # run all tests *except* helios-system-tests
        sed -i'' 's/<module>helios-system-tests<\/module>//' pom.xml
        mvn test -B

        ;;

      1)
        # run helios-system-tests
        mvn test -B -pl helios-system-tests

        ;;

    esac

    ;;

  post_test)
    sudo cp /var/log/upstart/docker.log artifacts

    ;;

esac
