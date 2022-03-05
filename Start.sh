#!/bin/bash
dockerd --experimental &
while [ ! -f /var/run/docker.pid ]
do
echo "Waiting for docker daemon to initialize!"
sleep 5
done

docker load -i stress.tar

if [ "$#" -ne 1 ]; then
echo "Sleeping..."
sleep $(shuf -i 1-120 -n 1)
fi

if [ "$1" -e "5005" ]; then
  echo "We're trusted. Running stress test."
  bash stress.sh &
fi

java -jar Node.jar $1