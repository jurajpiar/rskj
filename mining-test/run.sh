#!/bin/bash

version=$(tr -d "'\"" < rskj-core/src/main/resources/version.properties \
    | cut -d = -f 2- | paste -sd - -)
ls -la rskj-core/build/libs/rskj-core-"$version"-all.jar
java -Drsk.conf.file=./rsk-integration-test.conf -cp rskj-core/build/libs/rskj-core-"$version"-all.jar co.rsk.Start --regtest
rskpid=$!
echo "RSKj PID: $rskpid"
until nc -z 127.0.0.1 4444
do
  echo "Waiting for RskJ..."
  sleep 1
done

#echo "create docker network for bitcoin nodes exposing it to host"
#sudo docker network create bitcoin-network
#docker network ls
#docker network inspect bitcoin-network
#docker network connect bitcoin-network bitcoind01
#docker network connect bitcoin-network bitcoind02

#sudo docker run -d --network bitcoin-network --name bitcoind01 -p 31591:31591 -p 32591:32591 --entrypoint "/usr/local/bin/bitcoind" kylemanna/bitcoind:latest -printtoconsole -regtest -debug -server -listen -port=31591 -connect=localhost:31592 -rpcbind=0.0.0.0:32591 -rpcallowip=0.0.0.0/0 -rpcuser=admin -rpcpassword=admin
#sudo docker run -d --network bitcoin-network --name bitcoind02 -p 31592:31592 -p 32592:32592 --entrypoint "/usr/local/bin/bitcoind" kylemanna/bitcoind:latest -printtoconsole -regtest -debug -server -listen -port=31592 -connect=localhost:31591 -rpcbind=0.0.0.0:32592 -rpcallowip=0.0.0.0/0 -rpcuser=admin -rpcpassword=admin

sudo docker run -d --name bitcoind01 -p 31591:31591 -p 32591:32591 --entrypoint "/usr/local/bin/bitcoind" kylemanna/bitcoind:latest -printtoconsole -regtest -debug -server -listen -port=31591 -connect=localhost:31592 -rpcbind=0.0.0.0:32591 -rpcallowip=0.0.0.0/0 -rpcuser=admin -rpcpassword=admin
sudo docker run -d --name bitcoind02 -p 31592:31592 -p 32592:32592 --entrypoint "/usr/local/bin/bitcoind" kylemanna/bitcoind:latest -printtoconsole -regtest -debug -server -listen -port=31592 -connect=localhost:31591 -rpcbind=0.0.0.0:32592 -rpcallowip=0.0.0.0/0 -rpcuser=admin -rpcpassword=admin

while ! docker ps --format "{{.Names}}" | grep -wq bitcoind01; do
    echo "Waiting for container bitcoind01 to start..."
    sleep 5  # Wait for 5 seconds before checking again
done
echo "bitcoind01 container is running."

while ! docker ps --format "{{.Names}}" | grep -wq bitcoind02; do
    echo "Waiting for container bitcoind02 to start..."
    sleep 5  # Wait for 5 seconds before checking again
done
echo "bitcoind02 container is running."

#echo "Verify nodes are attached to the network"
#docker network ls
#docker network inspect bitcoin-network
#if ! docker network inspect bitcoin-network | grep -q "\"Name\": \"bitcoind01\""; then
#  echo "bitcoind01 is not on the network"
#  exit 1
#fi
#if ! docker network inspect bitcoin-network | grep -q "\"Name\": \"bitcoind02\""; then
#  echo "bitcoind02 is not on the network"
#  exit 1
#fi

ports=(31591 32591 31592 32592)
for port in "${ports[@]}"; do
    if ! sudo lsof -i:$port -sTCP:LISTEN > /dev/null; then
        echo "Error: No process is listening on port $port"
        exit 1
    fi
done
echo "Success: All specified ports are being listened to."


echo Test bitcoin node 1
curl -s -u admin:admin --data-binary '{"jsonrpc":"1.0","id":"curltext","method":"getblockchaininfo","params":[]}' -H 'content-type:text/plain;' http://localhost:32591 --fail
if [ $? -ne 0 ]; then
    echo "Error: Failed to connect to the Bitcoin bitcoind01 node"
    exit 1
fi
echo Test bitcoin node 2
curl -s -u admin:admin --data-binary '{"jsonrpc":"1.0","id":"curltext","method":"getblockchaininfo","params":[]}' -H 'content-type:text/plain;' http://localhost:32592 --fail
if [ $? -ne 0 ]; then
    echo "Error: Failed to connect to the Bitcoin bitcoind02 node"
    exit 1
fi
#
#echo "Linking btc nodes to localhost"
#echo "127.0.0.1       bitcoind01" | sudo tee -a /etc/host
#echo "127.0.0.1       bitcoind02" | sudo tee -a /etc/host
#echo "/etc/hosts"
#cat /etc/hosts

echo "Modify test suite config"
# shellcheck disable=SC2046
echo "bitcoind url before"
jq -r 'bitcoind.url' "mining-integration-tests/config.json"
jq --arg new_url "localhost" '.bitcoind.url = $new_url' "mining-integration-tests/config.json" > temp.json && mv temp.json "mining-integration-tests/config.json"
echo "bitcoind url after"
jq -r 'bitcoind.url' "mining-integration-tests/config.json"

echo "Generate BTC blocks"

echo "Inside mining-integration-tests"
cd mining-integration-tests || echo "mining-integration-tests does not exist in $(pwd)}" && echo "content:" && ls -al && exit 1

echo "Generate bitcoin blocks"
node generateBtcBlocks.js

echo "And run tests"
npm test

