#!/bin/bash
WORKER_IP=190
# vmIP=170

NODE_START=0
NODE_END=6

PORT_START=0
PORT_END=7

for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
do
	sudo ssh -p 20022 root@10.150.21.$(($WORKER_IP+$nodes)) 'cd /home/caslab/vm-openwhisk; ./destroy_vm.sh' &
done

echo "Sleep for 60s"
sleep 60

for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
do
	sudo ssh -p 20022 root@10.150.21.$(($WORKER_IP+$nodes)) 'cd /home/caslab/vm-openwhisk; ./start_vm.sh' &
done

wait < <(jobs -p)

echo "Sleep for 120s"
sleep 120
echo "Complete!"
