#!/bin/bash
INVOKERS=("caslab@10.150.21.190" "caslab@10.150.21.191" "caslab@10.150.21.192" "caslab@10.150.21.193" "caslab@10.150.21.194" "caslab@10.150.21.195" "caslab@10.150.21.196")

for INVOKER in "${INVOKERS[@]}"
	do
		ssh $INVOKER "cd ~/workspace/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed-jsq/ansible; ./build.sh" &
	done

./build.sh
