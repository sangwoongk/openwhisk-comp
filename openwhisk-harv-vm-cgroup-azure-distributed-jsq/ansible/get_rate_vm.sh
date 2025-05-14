#!/bin/bash
WORKER_IP=190

NODE_START=0
NODE_END=6	# at now, 6 is max

PORT_START=0
PORT_END=7	# 7 is max

# declare -A map

ps_single() {
	RES=$(sudo ssh -p $((3355+$2)) root@10.150.21.$(($WORKER_IP+$1)) 'cd /root/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed-jsq; cat overSubscribedRate')
	echo "[$(($WORKER_IP+$1)):$((3355+$2))] $RES"
}

for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
do
	for ((ip=$PORT_START;ip<=$PORT_END;ip++))
	do
		# KEY="$(($WORKER_IP+$nodes)):$((3355+$ip))"
		# map[$KEY]=$(sudo ssh -p $((3355+$ip)) root@10.150.21.$(($WORKER_IP+$nodes)) 'ps -ef | grep pickme' &)
		ps_single $nodes $ip &
	done
done

wait < <(jobs -p)

# for key in ${!map[@]}; do
# 	echo "[$key]" ${map[${key}]}
# done
