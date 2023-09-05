#!/bin/bash

workerip=190
vmip=170

node_end=6
port_end=7

# copy controller logs
cp /tmp/wsklogs/controller0/controller0_logs.log ./debug_logs/
for nodes in {0..6}
do
	for ip in {0..7}
	do
		# copy invoker logs
		sudo scp -P $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)):'/tmp/wsklogs/invoker'$(($nodes*($port_end+1)+$ip))'/invoker'$(($nodes*($port_end+1)+$ip))'_logs.log' ./debug_logs/ &
	done
done

wait < <(jobs -p)
echo "Complete!"
