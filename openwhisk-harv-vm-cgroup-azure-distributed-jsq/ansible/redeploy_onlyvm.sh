#!/bin/bash

#sudo ansible-playbook -i environments/local invoker.yml -e mode=clean
sudo ansible-playbook -i environments/local controller.yml -e mode=clean

./restart_vm.sh

sudo ansible-playbook -i environments/local controller.yml -e skip_pull_runtimes=true
#sudo ansible-playbook -i environments/local invoker.yml -e skip_pull_runtimes=true
