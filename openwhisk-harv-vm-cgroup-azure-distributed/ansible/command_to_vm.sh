#!/bin/bash

workerip=190
vmip=170

node_end=6
port_end=7

for nodes in {0..6}
do
	for ip in {0..7}
	do
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'apt install ntp ntpstat rdate ntpdate -y' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) '/etc/init.d/ntp stop' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'apt install chrony -y' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'chronyc tracking' &	# check chronyc working

		#echo $nodes, $ip
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'ntpdate 10.150.21.197' &
		#sleep 30

		# pull openwhisk-comp-pure
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'git clone https://github.com/sangwoongk/openwhisk-comp.git openwhisk-comp-pure' &
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk-comp-pure; git pull' &

		# pull images required to locust experiment
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull sangroad/sentiment:1.0.0' &
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull sangroad/markdown:1.0.0' &
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull sangroad/imgprocess:1.0.0' &
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull sangroad/imgresize:1.0.0' &
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull sangroad/chameleon:1.0.0' &
		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker pull openwhisk/action-python-v3.7:1.17.0' &

		#ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'docker rmi -f $(docker images -q)' &

		# copy files
		sudo scp -P $((3355+$ip)) ../core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerPool.scala root@10.150.21.$(($workerip+$nodes)):~/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed/core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerPool.scala &
		sudo scp -P $((3355+$ip)) ../core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerProxy.scala root@10.150.21.$(($workerip+$nodes)):~/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed/core/invoker/src/main/scala/org/apache/openwhisk/core/containerpool/ContainerProxy.scala &
		sudo scp -P $((3355+$ip)) ../common/scala/src/main/scala/org/apache/openwhisk/common/TransactionId.scala root@10.150.21.$(($workerip+$nodes)):~/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed/common/scala/src/main/scala/org/apache/openwhisk/common/TransactionId.scala &

		# extend LVM capacity
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'lvextend -l +90%FREE /dev/ubuntu-vg/ubuntu-lv'
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'resize2fs /dev/mapper/ubuntu--vg-ubuntu--lv'

		# pull latest pickme openwhisk
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric; git pull origin vm-nokafka' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric; git pull origin vm-metric' &
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'apt install -y linux-tools-common linux-tools-generic linux-tools-4.15.0-200-generic' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric; git config user.email "sw.kim@dgist.ac.kr"; git config user.name "Sangwoong Kim"' &
		# TODO: change VM config to `cpu-passthrough` and install g++-8 to build monitor

		# initial setup for pickme
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric; ./gradlew distDocker' &	# build all components
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric; ./gradlew :core:invoker:distDocker' &	# build invoker only
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed/; ./gradlew :core:invoker:distDocker' &	# build invoker only - mws
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk-comp/openwhisk-harv-vm-cgroup-azure-distributed-jsq/; ./gradlew :core:invoker:distDocker' &	# build invoker only - jsq
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'snap install cmake --classic' &
		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'git clone --recursive https://github.com/dmlc/xgboost; cd xgboost; mkdir build' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd xgboost/build; cmake ..; make install' &
		#sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'apt install -y libboost-iostreams-dev' &

		# sudo ssh -p $((3355+$ip)) root@10.150.21.$(($workerip+$nodes)) 'cd /root/openwhisk_metric/; git checkout vm-nokafka'
	done
done

wait < <(jobs -p)
echo "Complete!"
